package com.ysmef.geomodel.eftlm;

import java.util.List;

/** Gives one actionable reason for an EFTLM default-mesh fallback. */
public final class TlmMissingMeshDiagnosis {
    private TlmMissingMeshDiagnosis() {}

    /** Null means a server query or download is still in progress. */
    public static String explain(boolean transferPending, boolean sourceKnown, String transferFailure,
                                 String conversionFailure, List<String> unreadablePacks) {
        if (transferPending) return null;
        if (conversionFailure != null) {
            return "TLM model package found, but the Epic Fight mesh could not be built: "
                    + conversionFailure + "; inspect preceding conversion warnings";
        }
        if (sourceKnown) {
            return "TLM package is available, but no converted mesh was registered; inspect geometry and mesh-generation warnings";
        }
        if (unreadablePacks != null && !unreadablePacks.isEmpty()) {
            String first = unreadablePacks.get(0);
            String more = unreadablePacks.size() > 1 ? " (and " + (unreadablePacks.size() - 1) + " more)" : "";
            return "model not found in readable TLM packages; package " + first + more
                    + " could not be scanned. Check its ZIP/maid_model.json and preceding warnings"
                    + transferSuffix(transferFailure);
        }
        return "TLM model package is missing from this client and built-in resources"
                + transferSuffix(transferFailure)
                + "; place the matching TLM pack in tlm_custom_pack or enable server model transfer";
    }

    private static String transferSuffix(String reason) {
        return reason == null || reason.isBlank() ? "" : "; server transfer: " + reason;
    }
}
