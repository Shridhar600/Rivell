package com.rivell.workbench.contracts;

import java.io.InputStream;
import java.util.UUID;

public interface DocumentStructureParser {

    ParseResult parse(InputStream source, UUID contractVersionId);
}
