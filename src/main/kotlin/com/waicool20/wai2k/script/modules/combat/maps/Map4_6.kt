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

package com.waicool20.wai2k.script.modules.combat.maps

import com.waicool20.cvauto.android.AndroidRegion
import com.waicool20.wai2k.config.Wai2KConfig
import com.waicool20.wai2k.config.Wai2KProfile
import com.waicool20.wai2k.script.ScriptRunner
import com.waicool20.wai2k.script.modules.combat.MapRunner
import com.waicool20.waicoolutils.logging.loggerFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlin.random.Random

class Map4_6(
        scriptRunner: ScriptRunner,
        region: AndroidRegion,
        config: Wai2KConfig,
        profile: Wai2KProfile
) : MapRunner(scriptRunner, region, config, profile) {
    private val logger = loggerFor<Map4_6>()
    override val isCorpseDraggingMap = false

    override suspend fun execute() {
        if (gameState.requiresMapInit) {
            repeat(2) {
                region.pinch(
                        Random.nextInt(700, 800),
                        Random.nextInt(300, 400),
                        0.0,
                        500
                )
                delay(200)
            }
            logger.info("Resetting map")
            delay(1000)
            mapRunnerRegions.selectOperation.click(); yield()
            gameState.requiresMapInit = false           
        }
        else{
            nodes[0].findRegion()
            deployEchelons(nodes[0])
            // pan up
            val r = region.subRegionAs<AndroidRegion>(1058, 224, 100, 22)
            r.swipeTo(r.copy(y = r.y + 800))
            delay(500)
            val rEchelons = deployEchelons(nodes[1])
            mapRunnerRegions.startOperation.click(); yield()
            waitForGNKSplash()
            resupplyEchelons(rEchelons)
            planPath()
            waitForTurnEnd(4)
            handleBattleResults()
        }
    }

    private suspend fun planPath() {
        logger.info("Entering planning mode")
        mapRunnerRegions.planningMode.click(); yield()

        logger.info("Selecting ${nodes[4]}")
        nodes[4].findRegion().click(); yield()

        logger.info("Executing plan")
        mapRunnerRegions.executePlan.click()
    }
}