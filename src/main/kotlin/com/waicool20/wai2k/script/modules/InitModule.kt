/*
 * GPLv3 License
 *
 *  Copyright (c) WAI2K by waicool20
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.waicool20.wai2k.script.modules

import com.waicool20.wai2k.android.AndroidRegion
import com.waicool20.wai2k.config.Wai2KConfig
import com.waicool20.wai2k.config.Wai2KProfile
import com.waicool20.wai2k.game.GameState
import com.waicool20.wai2k.game.LocationId
import com.waicool20.wai2k.game.LogisticsSupport
import com.waicool20.wai2k.game.LogisticsSupport.Assignment
import com.waicool20.wai2k.script.Navigator
import com.waicool20.wai2k.script.ScriptStats
import com.waicool20.wai2k.util.Ocr
import com.waicool20.wai2k.util.doOCRAndTrim
import com.waicool20.wai2k.util.formatted
import com.waicool20.waicoolutils.DurationUtils
import com.waicool20.waicoolutils.logging.loggerFor
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import java.awt.image.BufferedImage
import java.time.Duration
import java.time.Instant

class InitModule(
        scriptStats: ScriptStats,
        gameState: GameState,
        region: AndroidRegion,
        config: Wai2KConfig,
        profile: Wai2KProfile,
        navigator: Navigator
) : ScriptModule(scriptStats, gameState, region, config, profile, navigator) {
    private val logger = loggerFor<InitModule>()
    override suspend fun execute() {
        navigator.checkLogistics()
        if (gameState.requiresUpdate) updateGameState()
    }

    private suspend fun updateGameState() {
        navigator.navigateTo(LocationId.HOME_STATUS)
        logger.info("Updating gamestate")
        val repairJob = launch { updateRepairs() }
        val logisticJob = launch { updateLogistics() }
        repairJob.join()
        logisticJob.join()
        logger.info("Finished updating game state")
        gameState.requiresUpdate = false
    }

    /**
     * Updates the logistic support in gamestate
     */
    private suspend fun updateLogistics() {
        logger.info("Reading logistics support status")
        // Optimize by taking a single screenshot and working on that
        val image = region.takeScreenshot()
        val entry = region.subRegion(485, 0, 229, region.h).findAllOrEmpty("init/logistics.png")
                // Map each region to whole logistic support entry
                .map { image.getSubimage(it.x - 130, it.y - 80, 853, 144) }
                .map {
                    listOf(
                            async {
                                // Echelon section on the right without the word "Echelon"
                                Ocr.forConfig(config).doOCRAndTrim(it.getSubimage(0, 26, 83, 118))
                            },
                            async {
                                // Logistics number ie. 1-1
                                Ocr.forConfig(config).doOCRAndTrim(it.getSubimage(120, 27, 105, 50))
                            },
                            async {
                                // Timer xx:xx:xx
                                Ocr.forConfig(config).doOCRAndTrim(it.getSubimage(593, 49, 200, 50))
                            }
                    )
                }
                .map { "${it[0].await()} ${it[1].await()} ${it[2].await()}" }
                .mapNotNull {
                    Regex("(\\d) (\\d)\\s?-\\s?(\\d) (\\d\\d):(\\d\\d):(\\d\\d)").matchEntire(it)?.destructured
                }
        // Clear existing timers
        gameState.echelons.forEach { it.logisticsSupportAssignment = null }
        entry.forEach { (sEchelon, sChapter, sNumber, sHour, sMinutes, sSeconds) ->
            val echelon = sEchelon.toInt()
            val logisticsSupport = LogisticsSupport.list[sChapter.toInt() * 4 + sNumber.toInt() - 1]
            val duration = DurationUtils.of(sSeconds.toLong(), sMinutes.toLong(), sHour.toLong())
            val eta = Instant.now() + duration
            logger.info("Echelon $echelon is doing logistics support ${logisticsSupport.number}, ETA: ${eta.formatted()}")
            gameState.echelons[echelon - 1].logisticsSupportAssignment = Assignment(logisticsSupport, eta)
        }
    }

    /**
     * Updates the repair timers in gamestate
     */
    private suspend fun updateRepairs() {
        logger.info("Reading repair status")
        // Optimize by taking a single screenshot and working on that
        val image = region.takeScreenshot()
        val firstEntryRegion = region.subRegion(315, 0, 159, region.h)
        // Find all the echelons that have a girl in repair
        val entries = firstEntryRegion.findAllOrEmpty("init/repairing.png") +
                firstEntryRegion.findAllOrEmpty("init/standby.png")

        // Map each region to whole logistic support entry
        val mappedEntries = entries
                .map { image.getSubimage(it.x - 111, it.y - 12, 1088, 144) }
                .map {
                    async {
                        // Echelon section on the right without the word "Echelon"
                        Ocr.forConfig(config).doOCRAndTrim(it.getSubimage(0, 25, 83, 119))
                    } to async { readRepairTimers(it) }
                }.map { it.first.await().toInt() to it.second.await() }

        // Clear existing timers
        gameState.echelons.flatMap { it.members }.forEach { it.repairEta = null }
        mappedEntries.forEach { (echelon, repairTimers) ->
            val members = gameState.echelons[echelon - 1].members
            logger.info("Echelon $echelon has repair timers: $repairTimers")
            repairTimers.forEach { (memberIndex, duration) ->
                members[memberIndex].repairEta = Instant.now() + duration
            }
        }
    }

    /**
     * Reads repair timers for a single echelon row
     *
     * @param image Region containing a single echelon row
     * @returns Map with member index as key and repair timer duration as value
     */
    private suspend fun readRepairTimers(image: BufferedImage): Map<Int, Duration> {
        val jobs = List(5) { entry ->
            async {
                // Single repair entry without the "Repairing" or "Standby"
                val timer = Ocr.forConfig(config).doOCRAndTrim(image.getSubimage(111 + 176 * entry, 82, 159, 51))
                Regex("(\\d\\d):(\\d\\d):(\\d\\d)").matchEntire(timer)?.groupValues?.let {
                    entry to DurationUtils.of(it[3].toLong(), it[2].toLong(), it[1].toLong())
                } ?: entry to Duration.ZERO
            }
        }
        return jobs.map { it.await() }.toMap()
    }
}