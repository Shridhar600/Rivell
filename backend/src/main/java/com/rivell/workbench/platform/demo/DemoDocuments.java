package com.rivell.workbench.platform.demo;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.ClassPathResource;

/** Classpath access to the committed demo fixture documents. */
final class DemoDocuments {

    static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    static final String VENDOR_NDA = "Vendor NDA - Ready for review.docx";
    static final String ACME_MSA = "Acme MSA - Review in progress.docx";
    static final String HOOLI_MSA = "Hooli MSA - Escalation blocking.docx";
    static final String PROCUREMENT_NDA = "Procurement NDA - Ready to finalize.docx";
    static final String ATLAS_MSA = "Atlas MSA - Finalized.docx";
    static final String EMPLOYEE_NDA = "Employee NDA - Finalized, no changes.docx";
    static final String PROCUREMENT_MSA = "Procurement MSA - Verified redline.docx";
    static final String GLOBEX_NDA = "Globex NDA - Redline failed.docx";
    static final String INITECH_NDA = "Initech NDA - Retry succeeded.docx";
    static final String UMBRELLA_MSA = "Umbrella MSA - Analysis failed.docx";
    static final String SOYLENT_MSA = "Soylent MSA - Limited analysis.docx";
    static final String VANDELAY_MSA = "Vandelay MSA - Analysis queued.docx";
    static final String CORRUPT_UPLOAD = "Corrupt Upload - Parse failed.docx";
    static final String SPARSE = "Minimal Sparse - Empty structure.docx";

    static final String PLAYBOOK = "Rivell Demo Playbook.docx";
    static final String PLAYBOOK_V2 = "Rivell Demo Playbook v2.docx";
    static final String CLAUSE_LIBRARY = "Rivell Demo Clause Library.docx";
    static final String DRAFT_PLAYBOOK = "Rivell Demo Draft Playbook.docx";
    static final String ROUTINE_PLAYBOOK = "Rivell Demo Routine Playbook.docx";
    static final String UNREADABLE_PLAYBOOK = "Unreadable Playbook.docx";

    private static final String PREFIX = "demo/documents/";

    private DemoDocuments() {
    }

    static InputStream open(String filename) throws IOException {
        var resource = new ClassPathResource(PREFIX + filename);
        if (!resource.exists()) {
            throw new IllegalStateException("Demo document missing from the classpath: " + filename);
        }
        return resource.getInputStream();
    }
}
