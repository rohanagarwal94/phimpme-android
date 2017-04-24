package vn.mbm.phimp.me.wordpress;

import com.android.volley.VolleyError;

public class VolleyUtils {

    public static int statusCodeFromVolleyError(VolleyError volleyError) {
        if (volleyError == null || volleyError.networkResponse == null) {
            return 0;
        }
        return volleyError.networkResponse.statusCode;
    }

}
