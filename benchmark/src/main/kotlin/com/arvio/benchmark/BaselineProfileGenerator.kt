package com.arvio.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val baselineRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineRule.collect(
            packageName = TARGET_PACKAGE,
            maxIterations = 5
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), 8_000)

            // Let home compose and initial rows settle.
            device.waitForIdle(1_000)
            if (device.wait(Until.hasObject(By.textContains("watching")), 1_200)) {
                device.pressDPadCenter()
                device.waitForIdle(1_000)
            }
            repeat(3) {
                device.pressDPadDown()
                device.waitForIdle(250)
            }
            repeat(3) {
                device.pressDPadUp()
                device.waitForIdle(250)
            }
        }
    }

    companion object {
        private const val TARGET_PACKAGE = "com.arvio.tv"
    }
}
