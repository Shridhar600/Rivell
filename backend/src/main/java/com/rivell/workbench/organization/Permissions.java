package com.rivell.workbench.organization;

public final class Permissions {

    public static final Permission CONTRACT_UPLOAD = new Permission("contracts:upload");
    public static final Permission CONTRACT_READ = new Permission("contracts:read");
    public static final Permission CONTRACT_ENRICH = new Permission("contracts:enrich");

    public static final Permission KNOWLEDGE_UPLOAD = new Permission("knowledge:upload");
    public static final Permission KNOWLEDGE_READ = new Permission("knowledge:read");
    public static final Permission KNOWLEDGE_REVIEW = new Permission("knowledge:review");
    public static final Permission KNOWLEDGE_PUBLISH = new Permission("knowledge:publish");

    public static final Permission RETRIEVAL_READ = new Permission("retrieval:read");

    private Permissions() {
    }
}
