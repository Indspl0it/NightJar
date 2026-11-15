package dev.nightjar.pduparse;

/**
 * WSP well-known values (WAP-230, OMNA registry): content types and push application ids. The strings are
 * the registry spellings; {@link WspParser#normalize(String)} gives the lower-case form the classifiers compare.
 *
 * <p>The content type codes are the ones of the framework's {@code WspTypeDecoder} table (OMNA "WSP Content Type
 * Codes"): 0x00 to 0x52, 0x0201 to 0x020C and 0x0300 to 0x031B. 0x0319 is left out: its registry spelling could not be
 * confirmed, so it stays an unnamed code.
 *
 * <p>Parallel arrays with a linear search keep the dex small; the tables are short and read once per push.
 *
 * <p>Thread: any.
 */
public final class WspTables {
    private static final int[] CONTENT_CODES = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F,
        0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F,
        0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F,
        0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F,
        0x50, 0x51, 0x52,
        0x0201, 0x0202, 0x0203, 0x0204, 0x0205, 0x0206, 0x0207, 0x0208, 0x0209, 0x020A, 0x020B, 0x020C,
        0x0300, 0x0301, 0x0302, 0x0303, 0x0304, 0x0305, 0x0306, 0x0307, 0x0308, 0x0309, 0x030A, 0x030B,
        0x030C, 0x030D, 0x030E, 0x030F, 0x0310, 0x0311, 0x0312, 0x0313, 0x0314, 0x0315, 0x0316, 0x0317,
        0x0318, 0x031A, 0x031B,
    };

    private static final String[] CONTENT_NAMES = {
        "*/*",
        "text/*",
        "text/html",
        "text/plain",
        "text/x-hdml",
        "text/x-ttml",
        "text/x-vCalendar",
        "text/x-vCard",
        "text/vnd.wap.wml",
        "text/vnd.wap.wmlscript",
        "text/vnd.wap.wta-event",
        "multipart/*",
        "multipart/mixed",
        "multipart/form-data",
        "multipart/byteranges",
        "multipart/alternative",
        "application/*",
        "application/java-vm",
        "application/x-www-form-urlencoded",
        "application/x-hdmlc",
        "application/vnd.wap.wmlc",
        "application/vnd.wap.wmlscriptc",
        "application/vnd.wap.wta-eventc",
        "application/vnd.wap.uaprof",
        "application/vnd.wap.wtls-ca-certificate",
        "application/vnd.wap.wtls-user-certificate",
        "application/x-x509-ca-cert",
        "application/x-x509-user-cert",
        "image/*",
        "image/gif",
        "image/jpeg",
        "image/tiff",
        "image/png",
        "image/vnd.wap.wbmp",
        "application/vnd.wap.multipart.*",
        "application/vnd.wap.multipart.mixed",
        "application/vnd.wap.multipart.form-data",
        "application/vnd.wap.multipart.byteranges",
        "application/vnd.wap.multipart.alternative",
        "application/xml",
        "text/xml",
        "application/vnd.wap.wbxml",
        "application/x-x968-cross-cert",
        "application/x-x968-ca-cert",
        "application/x-x968-user-cert",
        "text/vnd.wap.si",
        "application/vnd.wap.sic",
        "text/vnd.wap.sl",
        "application/vnd.wap.slc",
        "text/vnd.wap.co",
        "application/vnd.wap.coc",
        "application/vnd.wap.multipart.related",
        "application/vnd.wap.sia",
        "text/vnd.wap.connectivity-xml",
        "application/vnd.wap.connectivity-wbxml",
        "application/pkcs7-mime",
        "application/vnd.wap.hashed-certificate",
        "application/vnd.wap.signed-certificate",
        "application/vnd.wap.cert-response",
        "application/xhtml+xml",
        "application/wml+xml",
        "text/css",
        "application/vnd.wap.mms-message",
        "application/vnd.wap.rollover-certificate",
        "application/vnd.wap.locc+wbxml",
        "application/vnd.wap.loc+xml",
        "application/vnd.syncml.dm+wbxml",
        "application/vnd.syncml.dm+xml",
        "application/vnd.syncml.notification",
        "application/vnd.wap.xhtml+xml",
        "application/vnd.wv.csp.cir",
        "application/vnd.oma.dd+xml",
        "application/vnd.oma.drm.message",
        "application/vnd.oma.drm.content",
        "application/vnd.oma.drm.rights+xml",
        "application/vnd.oma.drm.rights+wbxml",
        "application/vnd.wv.csp+xml",
        "application/vnd.wv.csp+wbxml",
        "application/vnd.syncml.ds.notification",
        "audio/*",
        "video/*",
        "application/vnd.oma.dd2+xml",
        "application/mikey",
        "application/vnd.uplanet.cacheop-wbxml",
        "application/vnd.uplanet.signal",
        "application/vnd.uplanet.alert-wbxml",
        "application/vnd.uplanet.list-wbxml",
        "application/vnd.uplanet.listcmd-wbxml",
        "application/vnd.uplanet.channel-wbxml",
        "application/vnd.uplanet.provisioning-status-uri",
        "x-wap.multipart/vnd.uplanet.header-set",
        "application/vnd.uplanet.bearer-choice-wbxml",
        "application/vnd.phonecom.mmc-wbxml",
        "application/vnd.nokia.syncset+wbxml",
        "image/x-up-wpng",
        "application/iota.mmc-wbxml",
        "application/iota.mmc-xml",
        "application/vnd.syncml+xml",
        "application/vnd.syncml+wbxml",
        "text/vnd.wap.emn+xml",
        "text/calendar",
        "application/vnd.omads-email+xml",
        "application/vnd.omads-file+xml",
        "application/vnd.omads-folder+xml",
        "text/directory;profile=vCard",
        "application/vnd.wap.emn+wbxml",
        "application/vnd.nokia.ipdc-purchase-response",
        "application/vnd.motorola.screen3+xml",
        "application/vnd.motorola.screen3+gzip",
        "application/vnd.cmcc.setting+wbxml",
        "application/vnd.cmcc.bombing+wbxml",
        "application/vnd.docomo.pf",
        "application/vnd.docomo.ub",
        "application/vnd.omaloc-supl-init",
        "application/vnd.oma.group-usage-list+xml",
        "application/oma-directory+xml",
        "application/vnd.docomo.pf2",
        "application/vnd.oma.drm.roap-trigger+wbxml",
        "application/vnd.sbm.mid2",
        "application/vnd.wmf.bootstrap",
        "application/vnd.sbm.cid",
        "application/vnd.oma.bcast.provisioningtrigger",
    };

    private static final int[] APP_CODES = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x10};

    private static final String[] APP_NAMES = {
        "x-wap-application:*",
        "x-wap-application:push.sia",
        "x-wap-application:wml.ua",
        "x-wap-application:wta.ua",
        "x-wap-application:mms.ua",
        "x-wap-application:push.syncml",
        "x-wap-application:loc.ua",
        "x-wap-application:syncml.dm",
        "x-wap-application:drm.ua",
        "x-wap-application:emn.ua",
        "x-wap-application:wv.ua",
        "x-oma-application:ulp.ua",
    };

    private WspTables() {}

    /** Registry name of a well-known content type code, or {@code null} when the code is not assigned. */
    public static String contentTypeName(int code) {
        return lookup(CONTENT_CODES, CONTENT_NAMES, code);
    }

    /** Registry name of a well-known push application id, or {@code null} when the code is not assigned. */
    public static String applicationIdName(int code) {
        return lookup(APP_CODES, APP_NAMES, code);
    }

    private static String lookup(int[] codes, String[] names, int code) {
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == code) return names[i];
        }
        return null;
    }
}
