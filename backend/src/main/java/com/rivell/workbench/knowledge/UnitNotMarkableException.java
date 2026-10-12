package com.rivell.workbench.knowledge;

/**
 * A part that cannot be made a position: it already holds one, it was already corrected, it has
 * no text, its artifact type has no position gate, or its source no longer matches the part.
 */
public class UnitNotMarkableException extends RuntimeException {

    public UnitNotMarkableException(String message) {
        super(message);
    }
}
