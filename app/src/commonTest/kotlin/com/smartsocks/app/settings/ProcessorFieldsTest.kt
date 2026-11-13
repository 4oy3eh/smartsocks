package com.smartsocks.app.settings

import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.SettingsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessorFieldsTest {
    private val defaults = ProcessorSettings()

    @Test
    fun theDefaultsLieInsideTheSliderRanges() {
        for (field in ProcessorField.entries) {
            val v = field.valueIn(defaults)
            assertTrue(v in field.min..field.maxIn(defaults), "$field = $v")
            assertEquals(v, field.snap(v), "$field default is on a step")
            assertFalse(field.isChanged(defaults))
        }
    }

    @Test
    fun valuesAreSnappedToTheStepAndClampedToTheRange() {
        assertEquals(0.3, ProcessorField.CopCutoff.snap(0.33))
        assertEquals(0.1, ProcessorField.CopCutoff.snap(-5.0), "the filter cutoff stays positive")
        assertEquals(10.0, ProcessorField.CopCutoff.snap(99.0))
        assertEquals(2.5, ProcessorField.AutoZeroWindow.snap(2.6))
        assertEquals(0.35, ProcessorField.SwingFraction.snap(0.349))
        assertEquals(1.0, ProcessorField.SwingFraction.snap(1.7))
        assertEquals(0.07, ProcessorField.CopBeta.snap(0.0712))
        assertEquals(1.0, ProcessorField.ContactOn.snap(Double.NaN))
    }

    @Test
    fun theContactThresholdsStayOrdered() {
        // On below off drags off down.
        val lowered = ProcessorField.ContactOn.with(defaults, 6.0)
        assertEquals(6.0, lowered.contactOnThreshold)
        assertEquals(6.0, lowered.contactOffThreshold)
        // Off above on is capped at on.
        val capped = ProcessorField.ContactOff.with(defaults, 150.0)
        assertEquals(20.0, capped.contactOffThreshold)
        assertEquals(20.0, capped.contactOnThreshold)
        assertEquals(20.0, ProcessorField.ContactOff.maxIn(defaults))
        // Raising on leaves off alone.
        val raised = ProcessorField.ContactOn.with(defaults, 80.0)
        assertEquals(80.0, raised.contactOnThreshold)
        assertEquals(10.0, raised.contactOffThreshold)
    }

    @Test
    fun everyEditIsValidAndSurvivesTheSettingsFile() {
        var s = defaults
        val values = listOf(-100.0, 0.0, 0.04, 3.3, 77.7, 1e9)
        for (field in ProcessorField.entries) {
            for (v in values) {
                s = field.with(s, v) // ProcessorSettings' init would throw on an invalid combination
                assertTrue(field.valueIn(s) in field.min..field.max, "$field($v) = ${field.valueIn(s)}")
            }
        }
        assertTrue(s.contactOffThreshold <= s.contactOnThreshold)
        val stored = AppSettings(processor = s)
        assertEquals(stored, SettingsCodec.decode(SettingsCodec.encode(stored)))
    }

    @Test
    fun aChangedFieldIsFlaggedAndResetGivesTheDefaults() {
        val tuned = ProcessorField.CopBeta.with(ProcessorField.AutoZeroWindow.with(defaults, 0.0), 0.5)
        assertTrue(ProcessorField.CopBeta.isChanged(tuned))
        assertTrue(ProcessorField.AutoZeroWindow.isChanged(tuned))
        assertFalse(ProcessorField.ContactOn.isChanged(tuned))
        // "Reset to defaults" is ProcessorSettings(): nothing left flagged.
        assertTrue(ProcessorField.entries.none { it.isChanged(ProcessorSettings()) })
    }

    @Test
    fun valuesAreFormattedWithTheirUnits() {
        assertEquals("20 µS", ProcessorField.ContactOn.format(20.0))
        assertEquals("Off", ProcessorField.AutoZeroWindow.format(0.0))
        assertEquals("4.0 s", ProcessorField.AutoZeroWindow.format(4.0))
        assertEquals("30 %", ProcessorField.SwingFraction.format(0.3))
        assertEquals("1.5 Hz", ProcessorField.CopCutoff.format(1.5))
        assertEquals("0.05", ProcessorField.CopBeta.format(0.05))
        assertEquals("-0.50", fixed(-0.5, 2))
        assertEquals("0", fixed(0.2, 0))
    }
}
