package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BackendContractTest {
    @Test
    public void qnnStrictUsesPluginFixedDcrAndDisablesCpuFallback() {
        Backend backend = Backend.QNN_HTP_DCR_STRICT;

        assertTrue(backend.usesQnnPlugin());
        assertTrue(backend.isCpuFallbackDisabled());
        assertSame(ModelVariant.FIXED64_DCR_FULL, backend.modelVariant());
    }

    @Test
    public void nonQnnBackendsCannotEnterPluginRegistrationPath() {
        for (Backend backend : Backend.values()) {
            if (backend != Backend.QNN_HTP_DCR_STRICT
                    && backend != Backend.QNN_HTP_DCR_DIAGNOSTIC) {
                assertFalse(backend.name(), backend.usesQnnPlugin());
            }
        }
    }

    @Test
    public void qnnDiagnosticUsesSameGraphButAllowsVisibleCpuFallback() {
        Backend backend = Backend.QNN_HTP_DCR_DIAGNOSTIC;

        assertTrue(backend.usesQnnPlugin());
        assertFalse(backend.isCpuFallbackDisabled());
        assertSame(ModelVariant.FIXED64_DCR_FULL, backend.modelVariant());
    }
}
