package xposed.dummy;

import android.content.res.Resources;
import android.content.res.TypedArray;

/**
 *  This class is used as super class of XResources.XTypedArray.
 */
public class XTypedArraySuperClass extends TypedArray {
    protected XTypedArraySuperClass(Resources resources) {
        super(resources);
        throw new UnsupportedOperationException();
    }
}
