package com.rivell.workbench.foundation;

import java.io.Serializable;

@FunctionalInterface
public interface JobTask extends Serializable {

    void run() throws Exception;
}
