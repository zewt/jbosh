package com.kenai.jbosh;

/**
 * Data type representing the value of the {@code requests} attribute of the
 * {@code bosh} element.
 */
final class IntegerAttr extends AbstractIntegerAttr {
    
    /**
     * Creates a new attribute object.
     * 
     * @param val attribute value
     * @throws BOSHException on parse or validation failure
     */
    private IntegerAttr(final String val, final int minimumValue) throws BOSHException {
        super(val);
        checkMinValue(minimumValue);
    }

    /**
     * Creates a new attribute instance from the provided String.
     * 
     * @param str string representation of the attribute
     * @param minimumValue the minimum value to accept 
     * @return instance of the attribute for the specified string, or
     *  {@code null} if str is {@code null}
     * @throws BOSHException on parse or validation failure
     */
    static IntegerAttr createFromString(String str, int minimumValue) throws BOSHException {
        if (str == null)
            return null;
        else
            return new IntegerAttr(str, minimumValue);
    }
}
