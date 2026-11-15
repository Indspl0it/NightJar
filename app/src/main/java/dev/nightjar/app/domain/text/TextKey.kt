package dev.nightjar.app.domain.text

/**
 * Every user-facing string the classification layer produces.
 *
 * [resource] is the string resource name (the id lower-cased, `-` replaced by `_`), so a
 * localised resource wins as soon as it exists. [template] is the English fallback: it keeps unit tests, previews and
 * a resource-less build honest instead of printing a raw key. Placeholders are `{name}` and are filled by
 * [Explainer]; a placeholder left unfilled is a bug and `ExplainerTest` fails on it.
 */
enum class TextKey(
    val resource: String,
    val template: String,
) {
    // ---------------------------------------------------------------- class titles
    TITLE_CL_NORMAL("title_cl_normal", "Normal message"),
    TITLE_CL_SILENT("title_cl_silent", "Silent SMS (Type 0)"),
    TITLE_CL_FLASH("title_cl_flash", "Flash SMS (Class 0)"),
    TITLE_CL_REPLACE("title_cl_replace", "Replacement message"),
    TITLE_CL_MWI("title_cl_mwi", "Voicemail indicator"),
    TITLE_CL_VVM("title_cl_vvm", "Voicemail app control"),
    TITLE_CL_MISSED_CALL("title_cl_missed_call", "Missed-call notice"),
    TITLE_CL_CARRIER("title_cl_carrier", "Carrier app message"),
    TITLE_CL_RCS_PROV("title_cl_rcs_prov", "Chat (RCS) setup"),
    TITLE_CL_APP_TOKEN("title_cl_app_token", "App-only message"),
    TITLE_CL_DATA_PORT("title_cl_data_port", "Data message to port {port}"),

    /** [genericTitle] of [TITLE_CL_DATA_PORT]: a list without a message has no port to name. */
    TITLE_CL_DATA_PORT_GENERIC("title_cl_data_port_generic", "Data message to a port"),
    TITLE_CL_WAP_MMS("title_cl_wap_mms", "MMS notification"),
    TITLE_CL_WAP_SISL("title_cl_wap_sisl", "Web link push"),
    TITLE_CL_WAP_CP("title_cl_wap_cp", "Settings push (APN or proxy)"),
    TITLE_CL_WAP_DM("title_cl_wap_dm", "Device management request"),
    TITLE_CL_WAP_SUPL("title_cl_wap_supl", "Location request"),
    TITLE_CL_WAP_OTHER("title_cl_wap_other", "Push message ({contentType})"),
    TITLE_CL_WAP_UNREADABLE("title_cl_wap_unreadable", "Malformed WAP push: {anomaly}"),
    TITLE_CL_SIM_DD("title_cl_sim_dd", "SIM update from the network"),
    TITLE_CL_SIM_STORED("title_cl_sim_stored", "Saved to the SIM"),
    TITLE_CL_STATUS("title_cl_status", "Delivery report"),
    TITLE_CL_CB("title_cl_cb", "Cell broadcast (channel {channel})"),
    TITLE_CL_OEM("title_cl_oem", "Handled by the phone maker"),
    TITLE_CL_INJECTED("title_cl_injected", "Message added by an app ({app})"),
    TITLE_CL_MO_VISIBLE("title_cl_mo_visible", "Message sent by an app ({app})"),
    TITLE_CL_MO_UNPERSISTED("title_cl_mo_unpersisted", "Hidden message sent by an app ({app})"),
    TITLE_CL_MO_DATA("title_cl_mo_data", "Data message sent"),
    TITLE_CL_MO_RAW("title_cl_mo_raw", "Raw message sent by an app ({app})"),
    TITLE_CL_MO_STK("title_cl_mo_stk", "The SIM sent a message"),
    TITLE_CL_MO_VVM("title_cl_mo_vvm", "Voicemail setup message"),
    TITLE_CL_RETURN_CALL("title_cl_return_call", "Return call request"),
    TITLE_CL_ME_DOWNLOAD("title_cl_me_download", "Data download to the phone"),
    TITLE_CL_ME_DEPERSO("title_cl_me_deperso", "Phone unlock message (de-personalization)"),
    TITLE_CL_CLASS3("title_cl_class3", "Message for a connected device (Class 3)"),
    TITLE_CL_TELEMATIC("title_cl_telematic", "Telematic message"),
    TITLE_CL_EMPTY("title_cl_empty", "Empty message"),
    TITLE_CL_BINARY("title_cl_binary", "Binary message without a port"),
    TITLE_CL_COMPRESSED("title_cl_compressed", "Compressed message"),
    TITLE_CL_MULTIPART_INCOMPLETE("title_cl_multipart_incomplete", "Incomplete multipart message"),
    TITLE_CL_MALFORMED_SMS("title_cl_malformed_sms", "Malformed SMS: {anomaly}"),
    TITLE_CL_SMART("title_cl_smart", "Smart message"),
    TITLE_CL_LOC_SUPL("title_cl_loc_supl", "Location request (SUPL)"),
    TITLE_CL_WAP_CO("title_cl_wap_co", "Cache control push"),
    TITLE_CL_WAP_DS("title_cl_wap_ds", "Data sync request"),
    TITLE_CL_WAP_EMN("title_cl_wap_emn", "Email notification"),
    TITLE_CL_WAP_DRM("title_cl_wap_drm", "DRM licence"),
    TITLE_CL_WAP_VCARD("title_cl_wap_vcard", "Contact card push"),
    TITLE_CL_WAP_IMPS("title_cl_wap_imps", "Chat service wake-up"),

    // ---------------------------------------------------------------- subtype titles (ClassRegistry.subtype)
    TITLE_ST_SILENT_TYPE0_PORT("title_st_silent_type0_port", "Silent SMS (Type 0) to port {port}"),

    /** [genericTitle] of [TITLE_ST_SILENT_TYPE0_PORT]. */
    TITLE_ST_SILENT_TYPE0_PORT_GENERIC("title_st_silent_type0_port_generic", "Silent SMS (Type 0) to a port"),
    TITLE_ST_FLASH_CLASS0_EMPTY("title_st_flash_class0_empty", "Empty flash SMS (Class 0)"),
    TITLE_ST_REPLACE_TYPE("title_st_replace_type", "Replacement message (type {replaceType})"),
    TITLE_ST_SIM_DD_RFM("title_st_sim_dd_rfm", "SIM update from the network: changes files on the SIM"),
    TITLE_ST_SIM_DD_RAM("title_st_sim_dd_ram", "SIM update from the network: installs or deletes SIM apps"),
    TITLE_ST_SIM_DD_SAT("title_st_sim_dd_sat", "SIM update from the network: sends a command to the SIM browser (S@T)"),
    TITLE_ST_SIM_DD_WIB("title_st_sim_dd_wib", "SIM update from the network: sends a command to the SIM browser (WIB)"),
    TITLE_ST_SIM_DD_NOSEC("title_st_sim_dd_nosec", "SIM update from the network: has no security protection"),
    TITLE_ST_SIM_DD_OTHER("title_st_sim_dd_other", "SIM update from the network: sends data to a SIM app"),
    TITLE_ST_TELEMATIC_FAX("title_st_telematic_fax", "Telematic message: fax"),
    TITLE_ST_TELEMATIC_TELEX("title_st_telematic_telex", "Telematic message: telex"),
    TITLE_ST_TELEMATIC_PAGING("title_st_telematic_paging", "Telematic message: paging"),
    TITLE_ST_TELEMATIC_VOICE("title_st_telematic_voice", "Telematic message: voice"),
    TITLE_ST_TELEMATIC_ERMES("title_st_telematic_ermes", "Telematic message: ERMES"),
    TITLE_ST_TELEMATIC_X400("title_st_telematic_x400", "Telematic message: X.400"),
    TITLE_ST_TELEMATIC_EMAIL("title_st_telematic_email", "Telematic message: email"),
    TITLE_ST_TELEMATIC_OTHER("title_st_telematic_other", "Telematic message: other"),
    TITLE_ST_MWI_FAX("title_st_mwi_fax", "Fax waiting"),
    TITLE_ST_MWI_EMAIL("title_st_mwi_email", "Email waiting"),
    TITLE_ST_MWI_OTHER("title_st_mwi_other", "Message waiting"),
    TITLE_ST_MWI_DISCARD_SILENT("title_st_mwi_discard_silent", "Silent message-waiting signal"),
    TITLE_ST_STATUS_DELIVERED("title_st_status_delivered", "Delivery report: delivered"),
    TITLE_ST_STATUS_PENDING("title_st_status_pending", "Delivery report: pending"),
    TITLE_ST_STATUS_FAILED("title_st_status_failed", "Delivery report: failed"),
    TITLE_ST_DATA_PORT_WAP_PUSH("title_st_data_port_wap_push", "WAP push"),
    TITLE_ST_DATA_PORT_WAP_PUSH_SECURE("title_st_data_port_wap_push_secure", "WAP push (secure)"),
    TITLE_ST_DATA_PORT_WAP_CONNECTIONLESS_SESSION("title_st_data_port_wap_connectionless_session", "WAP connectionless session"),
    TITLE_ST_DATA_PORT_WAP_SESSION("title_st_data_port_wap_session", "WAP session"),
    TITLE_ST_DATA_PORT_WAP_CONNECTIONLESS_SESSION_SECURE(
        "title_st_data_port_wap_connectionless_session_secure",
        "WAP connectionless session (secure)",
    ),
    TITLE_ST_DATA_PORT_WAP_SESSION_SECURE("title_st_data_port_wap_session_secure", "WAP session (secure)"),
    TITLE_ST_DATA_PORT_WAP_VCARD_VCALENDAR("title_st_data_port_wap_vcard_vcalendar", "WAP vCard and vCalendar"),
    TITLE_ST_DATA_PORT_WAP_WTA_CONNECTIONLESS_SESSION_SECURE(
        "title_st_data_port_wap_wta_connectionless_session_secure",
        "WAP WTA connectionless session (secure)",
    ),
    TITLE_ST_DATA_PORT_WAP_WTA_SESSION_SECURE("title_st_data_port_wap_wta_session_secure", "WAP WTA session (secure)"),
    TITLE_ST_DATA_PORT_SERVICE_CARD("title_st_data_port_service_card", "Service card"),
    TITLE_ST_DATA_PORT_INTERNET_ACCESS_CONFIG("title_st_data_port_internet_access_config", "Internet access configuration"),
    TITLE_ST_DATA_PORT_DYNAMIC_MENU_CONTROL("title_st_data_port_dynamic_menu_control", "Dynamic menu control"),
    TITLE_ST_DATA_PORT_MESSAGE_ACCESS("title_st_data_port_message_access", "Message access"),
    TITLE_ST_DATA_PORT_EMAIL_NOTIFICATION("title_st_data_port_email_notification", "Email notification"),
    TITLE_ST_DATA_PORT_SYNCML_OTA_CONFIG("title_st_data_port_syncml_ota_config", "SyncML OTA configuration"),
    TITLE_ST_DATA_PORT_WAP_OTA_CONFIG("title_st_data_port_wap_ota_config", "WAP OTA configuration"),
    TITLE_ST_SMART_RINGTONE("title_st_smart_ringtone", "Ringtone"),
    TITLE_ST_SMART_OPLOGO("title_st_smart_oplogo", "Operator logo"),
    TITLE_ST_SMART_CLILOGO("title_st_smart_clilogo", "Caller logo"),
    TITLE_ST_SMART_PICTURE("title_st_smart_picture", "Picture message"),
    TITLE_ST_SMART_VCARD("title_st_smart_vcard", "Contact card"),
    TITLE_ST_SMART_VCAL("title_st_smart_vcal", "Calendar entry"),
    TITLE_ST_WAP_MMS_DELIVERY_REPORT("title_st_wap_mms_delivery_report", "MMS delivery report"),
    TITLE_ST_WAP_MMS_READ_REPORT("title_st_wap_mms_read_report", "MMS read report"),
    TITLE_ST_WAP_SISL_SI_DELETE("title_st_wap_sisl_si_delete", "Link deletion push"),
    TITLE_ST_WAP_SISL_SL("title_st_wap_sisl_sl", "Auto-open link push"),
    TITLE_ST_WAP_CP_NETWPIN("title_st_wap_cp_netwpin", "Settings push (APN or proxy), network PIN"),
    TITLE_ST_WAP_CP_USERPIN("title_st_wap_cp_userpin", "Settings push (APN or proxy), user PIN"),
    TITLE_ST_WAP_CP_USERNETWPIN("title_st_wap_cp_usernetwpin", "Settings push (APN or proxy), user and network PIN"),
    TITLE_ST_WAP_CP_USERPINMAC("title_st_wap_cp_userpinmac", "Settings push (APN or proxy), user PIN with MAC"),
    TITLE_ST_WAP_CP_NOSEC("title_st_wap_cp_nosec", "Settings push (APN or proxy), no security"),
    TITLE_ST_WAP_DM_BOOTSTRAP("title_st_wap_dm_bootstrap", "Device management setup"),
    TITLE_ST_WAP_SUPL_VERIFY("title_st_wap_supl_verify", "Location request (asks you)"),
    TITLE_ST_WAP_SUPL_SILENT("title_st_wap_supl_silent", "Silent location request"),
    TITLE_ST_WAP_VCARD_VCAL("title_st_wap_vcard_vcal", "Calendar push"),

    // ---------------------------------------------------------------- class explanations (EXP-*)
    EXP_CL_NORMAL(
        "exp_cl_normal",
        "Your messaging app showed this message. NightJar only counts these messages. NightJar does not record them.",
    ),
    EXP_CL_SILENT(
        "exp_cl_silent",
        "The network sent a Type 0 message (protocol identifier 0x40), and the phone did not show it. A sender can " +
            "use it to check that a phone is on, and it may show where the phone is. NightJar does not change the " +
            "confirmation that the phone sends to the network.",
    ),
    EXP_CL_FLASH(
        "exp_cl_flash",
        "The coding scheme of this message is class 0, so the phone shows it on the screen one time. Android normally " +
            "does not save it.",
    ),
    EXP_CL_REPLACE(
        "exp_cl_replace",
        "The protocol identifier of this message is a replace type. The phone replaces an earlier message of the same " +
            "type from the same sender. A sender can use it to change a message after you read it.",
    ),
    EXP_CL_MWI(
        "exp_cl_mwi",
        "This message sets a waiting indicator, for example the voicemail icon. The coding scheme or the message " +
            "header carries the indicator.",
    ),
    EXP_CL_VVM("exp_cl_vvm", "This message controls your visual voicemail app. Android gave it to that app and did not show it."),
    EXP_CL_MISSED_CALL(
        "exp_cl_missed_call",
        "This message is a notice about a call that you missed. Android made a missed-call entry from it and did not show a message.",
    ),
    EXP_CL_CARRIER(
        "exp_cl_carrier",
        "This message is for the carrier app on your phone ({package}). Android gave it to that app and did not show it.",
    ),
    EXP_CL_RCS_PROV("exp_cl_rcs_prov", "This message configures chat features (RCS) for your number."),
    EXP_CL_APP_TOKEN(
        "exp_cl_app_token",
        "This message is only for {app}. Apps usually use these messages for sign-in codes. Your messaging app does not show it.",
    ),
    EXP_CL_DATA_PORT(
        "exp_cl_data_port",
        "This message has data for port {port}, and Android does not show it. NightJar does not know an app that uses " +
            "this port. Some apps use data messages, and attackers may use them to track phones.",
    ),
    EXP_CL_WAP_MMS(
        "exp_cl_wap_mms",
        "This push message tells your messaging app about a picture message (MMS). It can be a new message, a " +
            "delivery report or a read report. Your messaging app downloads the MMS itself.",
    ),
    EXP_CL_WAP_SISL(
        "exp_cl_wap_sisl",
        "This push message has a web link (Service Indication). Android does not show these messages by default.",
    ),
    EXP_CL_WAP_CP(
        "exp_cl_wap_cp",
        "This push message tries to change your network settings (APN or proxy). A PIN protects it, but carriers " +
            "rarely send these messages today. An unexpected one can send your data through another server.",
    ),
    EXP_CL_WAP_DM(
        "exp_cl_wap_dm",
        "This push message asks the phone to contact a device management server, often the server of your carrier. " +
            "The server can change settings or install updates.",
    ),
    EXP_CL_WAP_SUPL(
        "exp_cl_wap_supl",
        "This push message is a location request (SUPL). A location server asks the phone for its position, and the " +
            "phone shows a notice. This is normal during emergency calls.",
    ),
    EXP_CL_WAP_UNREADABLE(
        "exp_cl_wap_unreadable",
        "NightJar cannot read this push message, because its header is wrong: {anomaly}. Attackers can use damaged " +
            "push messages to crash a phone. By default, NightJar blocks this type of message.",
    ),
    EXP_CL_WAP_OTHER(
        "exp_cl_wap_other",
        "This push message has content type {contentType}. NightJar does not know this content type. Android gives " +
            "it to the app that registered for that type, if one exists.",
    ),
    EXP_CL_SIM_DD(
        "exp_cl_sim_dd",
        "The protocol identifier of this message is 0x7F, so the phone gives it to the SIM. Carriers use these " +
            "messages to update the SIM. If you block it, the SIM may not get updates from the carrier.",
    ),
    EXP_CL_SIM_STORED(
        "exp_cl_sim_stored",
        "The coding scheme of this message is class 2, so the phone saved it on the SIM. Your messaging app may not " +
            "show it.",
    ),
    EXP_CL_STATUS(
        "exp_cl_status",
        "This is a delivery report for a message that this phone sent. It tells the phone if the message arrived.",
    ),
    EXP_CL_OEM("exp_cl_oem", "Software from the phone maker handled this message ({detail})."),
    EXP_CL_INJECTED(
        "exp_cl_injected",
        "This message did not come from the network. An app ({package}) added it to the SMS system of the phone. An " +
            "app can use this to make a false message look real.",
    ),
    EXP_CL_MO_VISIBLE(
        "exp_cl_mo_visible",
        "{app} sent a message to {number} without your messaging app. Android saved the message in your messages.",
    ),
    EXP_CL_MO_UNPERSISTED(
        "exp_cl_mo_unpersisted",
        "{app} sent a message to {number} without your messaging app. Android did not save the message in your " +
            "messages.",
    ),
    EXP_CL_MO_DATA(
        "exp_cl_mo_data",
        "{app} sent a data message to {number}, port {port}. Data messages never appear in your messaging app.",
    ),
    EXP_CL_MO_RAW("exp_cl_mo_raw", "{app} sent a raw SMS packet to {number}. It did not use the normal message API."),
    EXP_CL_MO_STK(
        "exp_cl_mo_stk",
        "The SIM asked the phone to send a message to {number}. You did not write this message. Attackers can use " +
            "this path (Simjacker) to send the location of the phone.",
    ),
    EXP_CL_MO_VVM("exp_cl_mo_vvm", "The phone sent a voicemail setup message to your carrier."),
    EXP_CL_RETURN_CALL(
        "exp_cl_return_call",
        "The protocol identifier of this message is 0x5F, a return call request. It asks you to call the sender.",
    ),
    EXP_CL_ME_DOWNLOAD(
        "exp_cl_me_download",
        "The protocol identifier of this message is 0x7D, so it sends data to the phone, not to you. The phone " +
            "software may use the data to change settings. Android does not show the message.",
    ),
    EXP_CL_ME_DEPERSO(
        "exp_cl_me_deperso",
        "The protocol identifier of this message is 0x7E, a de-personalization message. It can unlock the phone from " +
            "a network or a SIM. Carriers rarely send it.",
    ),
    EXP_CL_CLASS3(
        "exp_cl_class3",
        "The coding scheme of this message is class 3. Class 3 messages are for a device that you connect to the " +
            "phone, for example a computer. Android may show it as a normal message.",
    ),
    EXP_CL_TELEMATIC(
        "exp_cl_telematic",
        "The protocol identifier shows that this message came through a telematic gateway, for example a fax, email " +
            "or paging service. The phone may not show it as a normal message.",
    ),
    EXP_CL_EMPTY(
        "exp_cl_empty",
        "This message has a normal protocol identifier and coding scheme, but it has no text and no header. The phone " +
            "shows nothing. A sender can use an empty message to check that a phone is on.",
    ),
    EXP_CL_BINARY(
        "exp_cl_binary",
        "This message has 8-bit data but no port, so no app receives it. Android may show it as an empty or " +
            "unreadable message. A sender can use it to test how a phone handles data.",
    ),
    EXP_CL_COMPRESSED(
        "exp_cl_compressed",
        "The coding scheme shows that the sender compressed the text of this message. Android cannot read compressed " +
            "text, so the message may show as unreadable text.",
    ),
    EXP_CL_MULTIPART_INCOMPLETE(
        "exp_cl_multipart_incomplete",
        "Some parts of this multipart message did not arrive in 24 hours. Android cannot join the parts, so the " +
            "message may never show.",
    ),
    EXP_CL_MALFORMED_SMS(
        "exp_cl_malformed_sms",
        "NightJar cannot read this message, because its format is wrong: {anomaly}. Damaged messages can crash the " +
            "phone service. By default, NightJar blocks this type of message.",
    ),
    EXP_CL_SMART(
        "exp_cl_smart",
        "This message has data for the Smart Messaging port {port}. Old phones used these ports for ringtones, " +
            "logos, pictures and contact cards. Current phones may ignore the data.",
    ),
    EXP_CL_LOC_SUPL(
        "exp_cl_loc_supl",
        "This message has data for port {port}, the port for location requests (SUPL). A location server can use it " +
            "to ask the phone for its position. The phone may answer without a notice.",
    ),
    EXP_CL_WAP_CO(
        "exp_cl_wap_co",
        "This push message tells the phone browser to delete pages from its cache (Cache Operation). It has no text " +
            "for you.",
    ),
    EXP_CL_WAP_DS(
        "exp_cl_wap_ds",
        "This push message asks the phone to synchronize data, for example contacts, with a server (SyncML). It has " +
            "no text for you.",
    ),
    EXP_CL_WAP_EMN(
        "exp_cl_wap_emn",
        "This push message tells your email app that new email is waiting (Email Notification). It has no text for " +
            "you.",
    ),
    EXP_CL_WAP_DRM(
        "exp_cl_wap_drm",
        "This push message has a licence for protected media (DRM). An app on the phone uses the licence to play the " +
            "media.",
    ),
    EXP_CL_WAP_VCARD(
        "exp_cl_wap_vcard",
        "This push message has a contact card or a calendar entry. Android does not show these messages by default.",
    ),
    EXP_CL_WAP_IMPS(
        "exp_cl_wap_imps",
        "This push message wakes a chat service app on the phone (Wireless Village). It has no text for you.",
    ),

    // ---------------------------------------------------------------- subtype explanations and evidence lines
    EXP_ST_SILENT_TYPE0_PORT(
        "exp_st_silent_type0_port",
        "The network sent a Type 0 message (protocol identifier 0x40) to port {port}, and the phone did not show it. " +
            "A Type 0 message normally has no port. An app on the phone may receive the data.",
    ),
    EXP_ST_FLASH_CLASS0_EMPTY(
        "exp_st_flash_class0_empty",
        "The coding scheme of this message is class 0, but the message has no text. The phone shows nothing. A sender " +
            "can use an empty flash message to check that a phone is on.",
    ),
    EXP_ST_SIM_DD_RFM(
        "exp_st_sim_dd_rfm",
        "The network sent a command that changes files on the SIM. Carriers use these commands to update the SIM, for " +
            "example the roaming list. If you block it, the SIM may not get updates from the carrier.",
    ),
    EXP_ST_SIM_DD_RAM(
        "exp_st_sim_dd_ram",
        "The network sent a command that installs or deletes apps on the SIM. Carriers use these commands to add SIM " +
            "services. If you block it, the SIM may not get updates from the carrier.",
    ),
    EXP_ST_SIM_DD_SAT(
        "exp_st_sim_dd_sat",
        "The network sent a command to the S@T browser on the SIM. Attackers can use this path (Simjacker) to make " +
            "the SIM send the location of the phone. If you block it, the SIM may not get updates from the carrier.",
    ),
    EXP_ST_SIM_DD_WIB(
        "exp_st_sim_dd_wib",
        "The network sent a command to the WIB browser on the SIM. Attackers can use this path (WIBattack) to make " +
            "the SIM send messages or open web pages. If you block it, the SIM may not get updates from the carrier.",
    ),
    EXP_ST_SIM_DD_NOSEC(
        "exp_st_sim_dd_nosec",
        "The network sent a command to the SIM without a checksum and without encryption. Any sender can make a " +
            "command like this. If you block it, the SIM may not get updates from the carrier.",
    ),
    EXP_ST_SIM_DD_OTHER(
        "exp_st_sim_dd_other",
        "The network sent data to an app on the SIM. NightJar does not know the purpose of this SIM app. If you block " +
            "it, the SIM may not get updates from the carrier.",
    ),
    EXP_ST_MWI_DISCARD_SILENT(
        "exp_st_mwi_discard_silent",
        "The coding scheme tells the phone not to keep this message. The message has no text and does not change an " +
            "indicator. A sender can use it to check that a phone is on.",
    ),
    EXP_ST_DATA_PORT_KNOWN(
        "exp_st_data_port_known",
        "This message has data for port {port}, a standard WAP port. Android gives the data to the app for that port " +
            "and does not show it.",
    ),
    EXP_ST_DATA_PORT_PORT0(
        "exp_st_data_port_port0",
        "This message has data for port 0. Port 0 has no assigned use, so no app normally receives it. A sender can " +
            "use it to test how a phone handles data.",
    ),
    EXP_ST_DATA_PORT_SERVICE(
        "exp_st_data_port_service",
        "This message has data for port {port}. Phones keep this port for a system service, for example settings or " +
            "email notices. Android gives the data to the app for that port and does not show it.",
    ),
    EXP_ST_WAP_SISL_SI_DELETE(
        "exp_st_wap_sisl_si_delete",
        "This push message tells the phone to delete an earlier web link push (Service Indication). Android does not " +
            "show these messages by default.",
    ),
    EXP_ST_WAP_SISL_SL(
        "exp_st_wap_sisl_sl",
        "This push message has a web link that the phone can open without a tap (Service Loading). An attacker can " +
            "use it to open a harmful web page.",
    ),
    EXP_ST_WAP_CP_NOSEC(
        "exp_st_wap_cp_nosec",
        "This push message tries to change your network settings (APN or proxy), and no PIN protects it. Any sender " +
            "can make a message like this. It can send your data through a server that an attacker controls.",
    ),
    EXP_ST_WAP_DM_BOOTSTRAP(
        "exp_st_wap_dm_bootstrap",
        "This push message creates a device management account on the phone. The server of this account can then " +
            "change settings or install updates. An unexpected one may give another server control of the phone.",
    ),
    EXP_ST_WAP_SUPL_VERIFY(
        "exp_st_wap_supl_verify",
        "This push message is a location request (SUPL) that asks for your permission. A location server asks the " +
            "phone for its position. The phone asks you before it sends the position.",
    ),
    EXP_ST_WAP_SUPL_SILENT(
        "exp_st_wap_supl_silent",
        "This push message is a location request (SUPL) without a notice. A location server asks the phone for its " +
            "position, and the phone may answer without telling you. This is normal only during emergency calls.",
    ),
    EXP_ANOMALIES("exp_anomalies", "NightJar found these format errors: {anomalies}."),
    EXP_EXTRA_TYPES("exp_extra_types", "This message also matches these types: {types}."),

    // ---------------------------------------------------------------- anomaly names
    ANOMALY_ADDR_TRUNC("anomaly_name_addr_trunc", "address cut off"),
    ANOMALY_ADDR_LEN("anomaly_name_addr_len", "address too long"),
    ANOMALY_ADDR_TOA("anomaly_name_addr_toa", "unknown address type"),
    ANOMALY_PDU_SHORT("anomaly_name_pdu_short", "message too short"),
    ANOMALY_MTI("anomaly_name_mti", "unknown message type"),
    ANOMALY_PID_RSV("anomaly_name_pid_rsv", "unknown protocol identifier"),
    ANOMALY_DCS_RSV("anomaly_name_dcs_rsv", "unknown coding scheme"),
    ANOMALY_UDL_LONG("anomaly_name_udl_long", "length is more than the data"),
    ANOMALY_UDL_SHORT("anomaly_name_udl_short", "data is more than the length"),
    ANOMALY_UDH_LEN("anomaly_name_udh_len", "header length is wrong"),
    ANOMALY_IE_OVERRUN("anomaly_name_ie_overrun", "header element is too long"),
    ANOMALY_IE_RSV("anomaly_name_ie_rsv", "unknown header element"),
    ANOMALY_IE_DUP("anomaly_name_ie_dup", "repeated port element"),
    ANOMALY_PORT_ZERO("anomaly_name_port_zero", "port 0"),
    ANOMALY_CONCAT_BAD("anomaly_name_concat_bad", "bad multipart numbers"),
    ANOMALY_CONCAT_DUP("anomaly_name_concat_dup", "repeated multipart number"),
    ANOMALY_WSP_TYPE("anomaly_name_wsp_type", "unknown WSP type"),
    ANOMALY_WSP_HLEN("anomaly_name_wsp_hlen", "WSP header length is wrong"),
    ANOMALY_WSP_CTYPE("anomaly_name_wsp_ctype", "content type is unreadable"),
    ANOMALY_WSP_APPID("anomaly_name_wsp_appid", "application ID is unreadable"),
    ANOMALY_WBXML_VER("anomaly_name_wbxml_ver", "unknown WBXML version"),
    ANOMALY_WBXML_STR("anomaly_name_wbxml_str", "WBXML string table is wrong"),
    ANOMALY_WBXML_TOK("anomaly_name_wbxml_tok", "bad WBXML token"),
    ANOMALY_OTA_HDR("anomaly_name_ota_hdr", "SIM command header is wrong"),

    // ---------------------------------------------------------------- cell broadcast categories (strings_cb.xml)
    TITLE_CB_PRESIDENTIAL("title_cb_presidential", "Emergency alert: presidential"),
    TITLE_CB_EXTREME("title_cb_extreme", "Emergency alert: extreme"),
    TITLE_CB_SEVERE("title_cb_severe", "Emergency alert: severe"),
    TITLE_CB_AMBER("title_cb_amber", "Emergency alert: amber"),
    TITLE_CB_TEST("title_cb_test", "Emergency alert: test"),
    TITLE_CB_EXERCISE("title_cb_exercise", "Emergency alert: exercise"),
    TITLE_CB_PUBLIC_SAFETY("title_cb_public_safety", "Emergency alert: public safety"),
    TITLE_CB_OPERATOR("title_cb_operator", "Emergency alert: network operator"),
    TITLE_CB_ETWS_EQ("title_cb_etws_eq", "Earthquake warning"),
    TITLE_CB_ETWS_TSUNAMI("title_cb_etws_tsunami", "Tsunami warning"),
    TITLE_CB_ETWS_BOTH("title_cb_etws_both", "Earthquake and tsunami warning"),
    TITLE_CB_ETWS_TEST("title_cb_etws_test", "Test warning"),
    TITLE_CB_ETWS_OTHER("title_cb_etws_other", "Emergency warning"),
    TITLE_CB_AREA_INFO("title_cb_area_info", "Area information (channel {channel})"),
    TITLE_CB_CHANNEL("title_cb_channel", "Cell broadcast (channel {channel})"),
    TITLE_CB_SIM("title_cb_sim", "Cell broadcast for the SIM"),
    EXP_CB_CMAS(
        "exp_cb_cmas",
        "The public warning system sent this alert to all phones in the area on channel {channel}. NightJar never " +
            "blocks or holds an emergency alert.",
    ),
    EXP_CB_ETWS(
        "exp_cb_etws",
        "The earthquake and tsunami warning system sent this warning to all phones in the area on channel {channel}. " +
            "NightJar never blocks or holds an emergency warning.",
    ),
    EXP_CB_AREA_INFO(
        "exp_cb_area_info",
        "The network sent this message to all phones in the cell on channel {channel}. It can contain the name of " +
            "the area.",
    ),
    EXP_CB_CHANNEL(
        "exp_cb_channel",
        "The network sent this cell broadcast to all phones in the cell on channel {channel}. The network operator " +
            "can use a channel for local information.",
    ),
    EXP_CB_SIM(
        "exp_cb_sim",
        "The network sent this cell broadcast to the SIM, not to you. Message identifier {channel} is in the range " +
            "for data download to the SIM. A SIM app can use the data to change settings on the SIM.",
    ),

    // ---------------------------------------------------------------- fate lines (FATE-TXT-*)
    FATE_TXT_SHOWN("fate_txt_shown", "Shown in {app}"),
    FATE_TXT_APP("fate_txt_app", "Delivered to {app}"),
    FATE_TXT_NO_RECEIVER("fate_txt_no_receiver", "No app handles it, so Android did not keep it"),
    FATE_TXT_VVM("fate_txt_vvm", "Sent to the voicemail app"),
    FATE_TXT_CARRIER("fate_txt_carrier", "Sent to {app}"),
    FATE_TXT_MISSED_CALL("fate_txt_missed_call", "Shown as a missed-call notice"),
    FATE_TXT_SYSTEM("fate_txt_system", "Android received it and did not keep it"),
    FATE_TXT_SIM("fate_txt_sim", "Sent to the SIM card"),
    FATE_TXT_OEM_DROPPED("fate_txt_oem_dropped", "Software from the phone maker deleted it"),
    FATE_TXT_OEM_REDIRECTED("fate_txt_oem_redirected", "Software from the phone maker sent it to {app}"),

    /** The OEM redirect stage (HK-31) does not always name the package that got the message. */
    FATE_TXT_OEM_REDIRECTED_NO_APP("fate_txt_oem_redirected_no_app", "Software from the phone maker took it"),
    FATE_TXT_BLOCKLIST("fate_txt_blocklist", "Your blocked-numbers list blocked it"),
    FATE_TXT_BLOCKED("fate_txt_blocked", "NightJar blocked it ({rule})"),

    /** A block with no rule behind it (a malformed push, a self-test canary). */
    FATE_TXT_BLOCKED_NO_RULE("fate_txt_blocked_no_rule", "NightJar blocked it"),

    /** The outgoing-block gate stopped a send from an app that the user blocked. */
    FATE_TXT_BLOCKED_APP("fate_txt_blocked_app", "NightJar blocked it. You blocked {app} from sending messages."),
    FATE_TXT_QUARANTINED("fate_txt_quarantined", "On hold in NightJar"),
    FATE_TXT_RELEASED("fate_txt_released", "You released it on {date}"),
    FATE_TXT_NACK_STORAGE("fate_txt_nack_storage", "Android rejected it because phone storage is full"),
    FATE_TXT_INCOMPLETE("fate_txt_incomplete", "Parts still missing: {n}"),
    FATE_TXT_PENDING_UNLOCK("fate_txt_pending_unlock", "Android will process this after you unlock the phone"),
    FATE_TXT_SENT("fate_txt_sent", "Sent"),
    FATE_TXT_SEND_FAILED("fate_txt_send_failed", "Sending failed ({code})"),
    FATE_TXT_REJECTED("fate_txt_rejected", "Android rejected it and did not keep it"),
    FATE_TXT_ACCEPTED("fate_txt_accepted", "Android accepted it. NightJar did not see an app receive it."),
    FATE_TXT_NOT_DISPATCHED("fate_txt_not_dispatched", "Android did not deliver it to an app"),
    FATE_TXT_PHONE_STOPPED("fate_txt_phone_stopped", "The phone service stopped before the message had a result"),
    FATE_TXT_UNLOCK_EXPIRED(
        "fate_txt_unlock_expired",
        "Android kept it for the first unlock. NightJar stopped waiting for the result.",
    ),
    FATE_TXT_NOT_SENT("fate_txt_not_sent", "Android did not send it"),
    FATE_TXT_SEND_UNCONFIRMED("fate_txt_send_unconfirmed", "Android sent it to the modem. NightJar did not get a send result."),

    /** Guard text only. The app replaces FT-UNKNOWN with a true fate before it shows a message. */
    FATE_TXT_UNKNOWN("fate_txt_unknown", "NightJar did not see what happened to it"),

    // ---------------------------------------------------------------- why Android rejected a message
    FATE_REJECT_GENERIC("fate_reject_generic", "Android reported a general error."),
    FATE_REJECT_UNSUPPORTED("fate_reject_unsupported", "Android does not support this type of message."),
    FATE_REJECT_DUPLICATE("fate_reject_duplicate", "Android already received this message."),
    FATE_REJECT_DISPATCH("fate_reject_dispatch", "Android could not deliver the message."),
    FATE_REJECT_UNREADABLE("fate_reject_unreadable", "Android could not read the message."),
    FATE_REJECT_LOCKED("fate_reject_locked", "The phone storage was locked."),
    FATE_REJECT_DATABASE("fate_reject_database", "Android could not save the message."),

    // ---------------------------------------------------------------- fallbacks when a value is unknown
    GENERIC_UNKNOWN_APP("generic_unknown_app", "an app"),
    GENERIC_UNKNOWN("generic_unknown", "unknown"),
    GENERIC_UNKNOWN_RULE("generic_unknown_rule", "rule {id}"),
    GENERIC_NO_SENDER("generic_no_sender", "unknown sender"),
    GENERIC_DEFAULT_SMS_APP("generic_default_sms_app", "your messaging app"),

    /** APPDATA-R21: the label of an event whose class wire name this app version does not know. */
    GENERIC_UNKNOWN_CLASS("generic_unknown_class", "Unknown message type"),
    REASON_MALFORMED("reason_malformed", "This message has a damaged header. Attackers use damaged headers to crash phones."),
    REASON_SETTINGS_PUSH("reason_settings_push", "This message tries to change your network settings."),
    REASON_LOCATION("reason_location", "This message asks your phone for its location."),
    REASON_DEVICE_MGMT("reason_device_mgmt", "This message asks your phone to contact a device management server."),
    REASON_WEB_LINK("reason_web_link", "This message contains a web link."),
    REASON_HIDDEN_DATA("reason_hidden_data", "This message is for an app. It has no text."),
    REASON_NOBODY_HANDLES("reason_nobody_handles", "Nothing on your phone receives this message."),
    REASON_APP_ONLY("reason_app_only", "This message is for an app. Your messaging app does not show it."),
    REASON_SILENT("reason_silent", "Your phone receives this message but shows nothing."),
    REASON_FLASH("reason_flash", "This message shows on the screen. Android does not save it."),
    REASON_SIM("reason_sim", "This message is for your SIM card, not for you."),
    REASON_INJECTED("reason_injected", "An app added this message to your messages."),
    REASON_REPLACE("reason_replace", "This message can replace a message that is already on your phone."),
    REASON_SERVICE("reason_service", "This message is for your phone, not for you."),
    REASON_RARE_FORMAT("reason_rare_format", "This message uses a type that most phones do not receive."),
    REASON_INCOMPLETE("reason_incomplete", "Some parts of this message did not arrive."),
    REASON_BROADCAST("reason_broadcast", "The network sent this message to all phones in the area."),
    REASON_PUSH("reason_push", "This message sends data to an app on your phone."),
    REASON_PHONE_CHANGE("reason_phone_change", "This message tries to change a setting of your phone."),

    /** The reason lines of the rare formats name what the message contains, then that most phones do not get it. */
    REASON_RARE_CLASS3(
        "reason_rare_class3",
        "This message is for a device that you connect to the phone. Most phones do not receive this type.",
    ),
    REASON_RARE_TELEMATIC(
        "reason_rare_telematic",
        "This message came through a telematic service, for example fax or paging. Most phones do not receive this type.",
    ),
    REASON_RARE_EMPTY("reason_rare_empty", "This message has no text. Most phones do not receive this type."),
    REASON_RARE_BINARY("reason_rare_binary", "This message contains data but no port. Most phones do not receive this type."),
    REASON_RARE_COMPRESSED("reason_rare_compressed", "This message contains compressed text. Most phones do not receive this type."),
    REASON_RARE_SMART("reason_rare_smart", "This message contains Smart Messaging data. Most phones do not receive this type."),
    REASON_SMART_RINGTONE("reason_smart_ringtone", "This message contains a ringtone. Most phones do not receive this type."),
    REASON_SMART_OPLOGO("reason_smart_oplogo", "This message contains an operator logo. Most phones do not receive this type."),
    REASON_SMART_CLILOGO("reason_smart_clilogo", "This message contains a caller logo. Most phones do not receive this type."),
    REASON_SMART_PICTURE("reason_smart_picture", "This message contains a picture. Most phones do not receive this type."),
    REASON_SMART_VCARD("reason_smart_vcard", "This message contains a contact card. Most phones do not receive this type."),
    REASON_SMART_VCAL("reason_smart_vcal", "This message contains a calendar entry. Most phones do not receive this type."),

    // ---------------------------------------------------------------- Messages list rows only (05, ListTitle)

    /** Short list titles where cutting the subtype off the full title is not enough (max 24 characters). */
    TITLE_LIST_DATA_PORT("title_list_data_port", "Data to port {port}"),
    TITLE_LIST_DEVICE_MGMT("title_list_device_mgmt", "Device management"),
    TITLE_LIST_SIM_UPDATE("title_list_sim_update", "SIM update"),
    TITLE_LIST_OEM("title_list_oem", "Phone maker message"),
    TITLE_LIST_MO_HIDDEN("title_list_mo_hidden", "Hidden sent message"),
    TITLE_LIST_MO_RAW("title_list_mo_raw", "Raw sent message"),
    TITLE_LIST_ME_DOWNLOAD("title_list_me_download", "Data download"),
    TITLE_LIST_CLASS3("title_list_class3", "Message for a device"),
    TITLE_LIST_BINARY("title_list_binary", "Binary message"),
    TITLE_LIST_INCOMPLETE("title_list_incomplete", "Incomplete message"),
    TITLE_LIST_FLASH("title_list_flash", "Flash SMS"),
    TITLE_LIST_MWI_SILENT("title_list_mwi_silent", "Silent voicemail signal"),
    TITLE_LIST_INTERNET_CONFIG("title_list_internet_config", "Internet settings"),
    TITLE_LIST_QUAKE_TSUNAMI("title_list_quake_tsunami", "Earthquake and tsunami"),
    TITLE_LIST_CB_SIM("title_list_cb_sim", "Cell broadcast for SIM"),

    /** List descriptions that start with the detail the short title drops. */
    REASON_LIST_MALFORMED("reason_list_malformed", "{anomaly}. A damaged header like this can crash a phone."),
    REASON_LIST_BINARY("reason_list_binary", "Data with no port. Most phones do not receive this type."),
    REASON_LIST_TELEMATIC("reason_list_telematic", "{detail} service. Most phones do not receive this type."),
    REASON_LIST_FLASH_EMPTY("reason_list_flash_empty", "Empty. It shows on the screen, and Android does not save it."),
    REASON_LIST_WAP_SECURE("reason_list_wap_secure", "Secure session for an app. It has no text."),

    /** The subtype words after the ":" of a full title, as the first sentence of the list description. */
    REASON_LIST_DETAIL("reason_list_detail", "{detail}."),
    REASON_LIST_APP_SENT("reason_list_app_sent", "{app} sent this message."),
    REASON_LIST_APP_ADDED("reason_list_app_added", "{app} added this message."),

    // ---------------------------------------------------------------- severity labels
    SEVERITY_INFO("severity_info", "Normal"),
    SEVERITY_NOTABLE("severity_notable", "Unusual"),
    SEVERITY_SUSPICIOUS("severity_suspicious", "Suspicious"),
    SEVERITY_HIGH("severity_high", "High"),

    // ---------------------------------------------------------------- class families (grouping)
    FAMILY_SILENT("family_silent", "Silent SMS"),
    FAMILY_SIM("family_sim", "SIM messages"),
    FAMILY_WAP("family_wap", "Push messages"),
    FAMILY_VOICEMAIL("family_voicemail", "Voicemail"),
    FAMILY_APP("family_app", "App messages"),
    FAMILY_BROADCAST("family_broadcast", "Broadcasts"),
    FAMILY_OUTBOUND("family_outbound", "Sent by apps"),
    FAMILY_SYSTEM("family_system", "System"),

    // ---------------------------------------------------------------- protection copy (MSG-*)
    MSG_ST_PROT("msg_st_prot", "Protected"),
    MSG_ST_PROT_SUB("msg_st_prot_sub", "Monitoring {subs}"),
    MSG_ST_PAUSED("msg_st_paused", "Blocking paused"),
    MSG_ST_PAUSED_SUB("msg_st_paused_sub", "NightJar still monitors messages. Turn off safe mode to block again."),
    HERO_NA_TITLE("hero_na_title", "NightJar is not active"),
    HERO_NA_SUB(
        "hero_na_sub",
        "Open Vector or LSPosed. Turn on the NightJar module with scope Phone Services. Then reboot the phone.",
    ),
    HERO_UNS_TITLE("hero_uns_title", "NightJar does not support this build"),
    MSG_NA_01(
        "msg_na_01",
        "NightJar is not active. Open Vector or LSPosed. Turn on the NightJar module with scope Phone Services. Then reboot the phone.",
    ),
    MSG_SM_01("msg_sm_01", "Safe mode is on. NightJar does not block messages. NightJar still records them."),
    MSG_UNS_01(
        "msg_uns_01",
        "NightJar does not support this build yet. The module stopped itself in the phone service. Share diagnostics to help add support.",
    ),
    MSG_FW_01("msg_fw_01", "NightJar cannot block messages on this build. NightJar still records them."),
    MSG_UPD_01("msg_upd_01", "The module is newer than the app. Update NightJar to see all details."),
    MSG_UNKNOWN_SUBS("msg_unknown_subs", "your SIMs"),

    /** One SIM slot for a status line, see [simNames]. {n} is the 1-based slot number. */
    SIM_SLOT_NAME("sim_slot_name", "SIM {n}"),

    /** Two SIM names joined, see [simNames]. */
    SIM_PAIR_NAMES("sim_pair_names", "{first} and {second}"),

    // ---------------------------------------------------------------- detection paragraphs (06)
    DET_SILENT_BURST("det_silent_burst", "{count} silent SMS in {minutes} minutes from {senders} senders."),
    DET_SILENT_NEW_SENDER("det_silent_new_sender", "This is the first silent SMS from a sender that is new to this phone."),
    DET_SILENT_ROAMING("det_silent_roaming", "The phone is roaming, so a silent SMS is more likely to be a location check."),
    DET_SIM_OTA_INSECURE("det_sim_ota_insecure", "This SIM update has no integrity check and no encryption. Anyone could have sent it."),
    DET_SIM_OTA_TAR("det_sim_ota_tar", "It targets {tar}, the application that a SIM-toolkit attack uses to send messages."),
    DET_OMA_CP_UNKNOWN("det_oma_cp_unknown", "The sender is not a known carrier number."),
    DET_OMA_CP_VALUES("det_oma_cp_values", "It tries to set the APN to {apn}, the proxy to {proxy} and the server to {server}."),
    DET_OMA_CP_KNOWN_CARRIER("det_oma_cp_known_carrier", "A known carrier number sent this message."),
    DET_SUPL_EMERGENCY(
        "det_supl_emergency",
        "An emergency call is active or ended a short time ago. The network expects this location request.",
    ),
    DET_SUPL_UNEXPECTED("det_supl_unexpected", "No emergency call is active. Nothing on this phone asked for its location."),
    DET_OUTBOUND_SHORT_CODE("det_outbound_short_code", "The destination is a short code, which can carry a charge."),
    DET_OUTBOUND_PREMIUM("det_outbound_premium", "The destination matches a charged short code or a premium-rate number."),
    DET_OUTBOUND_FIRST_SEND("det_outbound_first_send", "This app has never sent a message on this phone before."),
    DET_INJECTED_UNTRUSTED("det_injected_untrusted", "NightJar does not trust this app to add messages."),

    // ---------------------------------------------------------------- suspicious patterns
    PAT_SILENT_BURST("pat_silent_burst", "Repeated silent SMS can show where a phone is."),
    PAT_SILENT_UNKNOWN_SENDER("pat_silent_unknown_sender", "The sender is not in Known senders."),
    PAT_SUPL_UNKNOWN_SENDER("pat_supl_unknown_sender", "The sender is not a known carrier number."),
    PAT_CP_NO_NETWORK_PIN("pat_cp_no_network_pin", "A settings push without a network PIN came from an unknown sender."),
    PAT_SIM_BROWSER_SAT("pat_sim_browser_sat", "A message sent a command to the S@T browser on the SIM."),
    PAT_SIM_BROWSER_WIB("pat_sim_browser_wib", "A message sent a command to the WIB browser on the SIM."),
    PAT_SIM_SENDS_AFTER_UPDATE(
        "pat_sim_sends_after_update",
        "The SIM sent a message after it received an update. This is the Simjacker pattern.",
    ),
    PAT_UNKNOWN_PORT_REPEAT("pat_unknown_port_repeat", "The same sender sent {count} messages to an unknown port in 24 hours."),
    PAT_MALFORMED_BURST("pat_malformed_burst", "The same phone received {count} malformed messages in 10 minutes."),
    PAT_REPLACE_NO_FIRST("pat_replace_no_first", "No earlier message of this type came from this sender."),

    // ---------------------------------------------------------------- notifications
    // The resources are in strings_notifications.xml. Title = the type name and what NightJar did
    // ("Settings push blocked"). Text = one sentence: what the message tried to do, or what the user can do.
    NOTIF_PUBLIC_SUSPICIOUS("notif_public_suspicious", "NightJar found a suspicious message"),
    NOTIF_PUBLIC_ACTIVITY("notif_public_activity", "NightJar activity"),
    NOTIF_CHANNEL_GROUP("notif_channel_group", "NightJar"),
    NOTIF_CHANNEL_SUSPICIOUS("notif_channel_suspicious", "Suspicious messages"),
    NOTIF_CHANNEL_SUSPICIOUS_DESC(
        "notif_channel_suspicious_desc",
        "NightJar notifies you about suspicious hidden messages and detections.",
    ),
    NOTIF_CHANNEL_HIDDEN("notif_channel_hidden", "Hidden messages"),
    NOTIF_CHANNEL_HIDDEN_DESC("notif_channel_hidden_desc", "NightJar notifies you quietly about unusual hidden messages."),
    NOTIF_CHANNEL_FIREWALL("notif_channel_firewall", "Blocking activity"),
    NOTIF_CHANNEL_FIREWALL_DESC("notif_channel_firewall_desc", "NightJar shows summaries of the messages that it held or blocked."),
    NOTIF_CHANNEL_BROADCAST("notif_channel_broadcast", "Broadcast copies"),
    NOTIF_CHANNEL_BROADCAST_DESC("notif_channel_broadcast_desc", "NightJar shows a copy of each cell broadcast that the phone receives."),
    NOTIF_CHANNEL_STATUS("notif_channel_status", "Protection status"),
    NOTIF_CHANNEL_STATUS_DESC("notif_channel_status_desc", "NightJar tells you when protection does not work."),
    NOTIF_CHANNEL_DIGEST("notif_channel_digest", "Digest"),
    NOTIF_CHANNEL_DIGEST_DESC("notif_channel_digest_desc", "NightJar shows a daily or weekly summary."),
    NOTIF_FROM("notif_from", "From {who}"),
    NOTIF_TO("notif_to", "To {who}"),
    NOTIF_SOME_APP("notif_some_app", "An app"),
    NOTIF_TITLE_BLOCKED("notif_title_blocked", "{what} blocked"),
    NOTIF_TITLE_HELD("notif_title_held", "{what} on hold"),
    NOTIF_TITLE_RECEIVED("notif_title_received", "{what} received"),
    NOTIF_TITLE_SENT("notif_title_sent", "{what} sent"),
    NOTIF_HELD_ALLOW("notif_held_allow", "Allow it or block it."),
    NOTIF_HELD_ALLOW_FROM("notif_held_allow_from", "From {who}. Allow it or block it."),
    NOTIF_HELD_OPEN("notif_held_open", "Open it or block it."),
    NOTIF_HELD_OPEN_FROM("notif_held_open_from", "From {who}. Open it or block it."),
    NOTIF_WHAT_NORMAL("notif_what_normal", "Normal message"),
    NOTIF_WHY_NORMAL("notif_why_normal", "It reached your phone as a normal message."),
    NOTIF_WHAT_SILENT("notif_what_silent", "Silent SMS"),
    NOTIF_WHY_SILENT("notif_why_silent", "It reached your phone and showed you nothing."),
    NOTIF_WHAT_FLASH("notif_what_flash", "Flash SMS"),
    NOTIF_WHY_FLASH("notif_why_flash", "Android showed it on your screen but did not save it."),
    NOTIF_WHAT_REPLACE("notif_what_replace", "Replacement message"),
    NOTIF_WHY_REPLACE("notif_why_replace", "It can replace a message that is already on your phone."),
    NOTIF_WHAT_SETTINGS("notif_what_settings", "Settings push"),
    NOTIF_WHY_SETTINGS("notif_why_settings", "It tried to change your network settings."),
    NOTIF_WHAT_LOCATION("notif_what_location", "Location request"),
    NOTIF_WHY_LOCATION("notif_why_location", "It asked your phone for its location."),
    NOTIF_WHAT_REMOTE("notif_what_remote", "Device management request"),
    NOTIF_WHY_REMOTE("notif_why_remote", "It tried to manage your phone from the network."),
    NOTIF_WHAT_WEB_LINK("notif_what_web_link", "Web link push"),
    NOTIF_WHY_WEB_LINK("notif_why_web_link", "It sent a web link to your phone."),
    NOTIF_WHAT_MMS("notif_what_mms", "MMS notification"),
    NOTIF_WHY_MMS("notif_why_mms", "It told your phone to download an MMS."),
    NOTIF_WHAT_PUSH("notif_what_push", "Push message"),
    NOTIF_WHY_PUSH("notif_why_push", "It sent data to an app on your phone."),
    NOTIF_WHAT_SIM("notif_what_sim", "SIM card message"),
    NOTIF_WHY_SIM("notif_why_sim", "It sent data to your SIM card."),
    NOTIF_WHAT_PHONE("notif_what_phone", "Phone settings message"),
    NOTIF_WHY_PHONE("notif_why_phone", "It tried to change a setting of your phone."),
    NOTIF_WHAT_APP_DATA("notif_what_app_data", "Hidden app message"),
    NOTIF_WHY_APP_DATA("notif_why_app_data", "It is for an app and has no text."),
    NOTIF_WHAT_INJECTED("notif_what_injected", "App-added message"),
    NOTIF_WHY_INJECTED("notif_why_injected", "An app added this message to your messages."),
    NOTIF_WHAT_DAMAGED("notif_what_damaged", "Malformed message"),
    NOTIF_WHY_DAMAGED("notif_why_damaged", "Its damaged header can crash a phone."),
    NOTIF_WHAT_INCOMPLETE("notif_what_incomplete", "Incomplete message"),
    NOTIF_WHY_INCOMPLETE("notif_why_incomplete", "Some parts of it did not arrive."),
    NOTIF_WHAT_VOICEMAIL("notif_what_voicemail", "Voicemail message"),
    NOTIF_WHY_VOICEMAIL("notif_why_voicemail", "It is a message for your voicemail service."),
    NOTIF_WHAT_SERVICE("notif_what_service", "Service message"),
    NOTIF_WHY_SERVICE("notif_why_service", "It is a message for your phone, not for you."),
    NOTIF_WHAT_RARE("notif_what_rare", "Unusual message"),
    NOTIF_WHY_RARE("notif_why_rare", "Most phones do not receive this type of message."),
    NOTIF_WHAT_BROADCAST("notif_what_broadcast", "Cell broadcast"),
    NOTIF_WHY_BROADCAST("notif_why_broadcast", "The network sent this message to all phones in the area."),
    NOTIF_WHAT_OUT_HIDDEN("notif_what_out_hidden", "Hidden message"),
    NOTIF_WHY_OUT_HIDDEN("notif_why_out_hidden", "{app} sends this message in the background."),
    NOTIF_WHAT_OUT_DATA("notif_what_out_data", "Data message"),
    NOTIF_WHY_OUT_DATA("notif_why_out_data", "{app} sends this data message in the background."),
    NOTIF_WHAT_OUT_APP("notif_what_out_app", "App message"),
    NOTIF_WHY_OUT_APP("notif_why_out_app", "{app} sends this message."),
    NOTIF_WHAT_OUT_SIM("notif_what_out_sim", "SIM card message"),
    NOTIF_WHY_OUT_SIM("notif_why_out_sim", "Your SIM card sends this message."),
    NOTIF_WHAT_OUT_VOICEMAIL("notif_what_out_voicemail", "Voicemail setup message"),
    NOTIF_WHY_OUT_VOICEMAIL("notif_why_out_voicemail", "Your phone sends this message to configure voicemail."),
    NOTIF_DIGEST_TITLE("notif_digest_title", "Weekly summary"),
    NOTIF_DIGEST_TEXT("notif_digest_text", "Hidden messages: {hidden}. Suspicious: {suspicious}. On hold: {held}. Blocked: {blocked}."),
    NOTIF_STATUS_NOT_ACTIVE("notif_status_not_active", "NightJar is not active"),
    NOTIF_STATUS_NOT_ACTIVE_TEXT("notif_status_not_active_text", "NightJar cannot see or block messages now. Open NightJar to fix this."),
    NOTIF_STATUS_SAFE_MODE("notif_status_safe_mode", "NightJar is in safe mode"),
    NOTIF_STATUS_SAFE_MODE_TEXT("notif_status_safe_mode_text", "NightJar records messages but does not block them."),
    NOTIF_STATUS_FIREWALL_OFF("notif_status_firewall_off", "NightJar cannot block messages"),
    NOTIF_STATUS_FIREWALL_OFF_TEXT("notif_status_firewall_off_text", "NightJar records messages but cannot block them on this phone."),
    NOTIF_STATUS_VERSION("notif_status_version", "Update the NightJar app"),
    NOTIF_STATUS_VERSION_TEXT("notif_status_version_text", "The module is newer than the app. Update the app to see all details."),
    NOTIF_ACTION_OPEN("notif_action_open", "Open"),
    NOTIF_ACTION_ALLOW("notif_action_allow", "Allow"),
    NOTIF_ACTION_BLOCK("notif_action_block", "Block"),
    NOTIF_BEFORE_UNLOCK("notif_before_unlock", "Unlock the phone to see the details."),

    // ---------------------------------------------------------------- release failures (ReleaseFailureText)
    // The resources are the ones that the detail screen shows (strings_detail.xml), so the copy has one source.
    RELEASE_FAILED("release_failed", "NightJar did not release the message. The message is still on hold."),
    RELEASE_NOT_FOUND("release_not_found", "NightJar cannot find this message."),
    RELEASE_NOT_RELEASABLE(
        "release_not_releasable",
        "NightJar cannot release this type of message. The message is still on hold.",
    ),
    RELEASE_NO_PDU("release_no_pdu", "NightJar has no copy of this message, so it cannot release it."),
    RELEASE_NOT_ACTIVE(
        "release_not_active",
        "The module is not active, so NightJar cannot release the message. The message is still on hold.",
    ),
    RELEASE_TIMEOUT("release_timeout", "The phone did not answer in time. Try again. The message is still on hold."),
    RELEASE_NO_DISPATCHER(
        "release_no_dispatcher",
        "The phone did not accept the message for delivery. The message is still on hold.",
    ),
    RELEASE_REJECTED(
        "release_rejected",
        "The phone did not accept the release request from NightJar. The message is still on hold.",
    ),

    // ---------------------------------------------------------------- module / root management
    ROOT_MANAGER_MAGISK("root_manager_magisk", "Magisk"),
    ROOT_MANAGER_KERNELSU("root_manager_kernelsu", "KernelSU"),
    ROOT_MANAGER_APATCH("root_manager_apatch", "APatch"),
    ROOT_MANAGER_UNKNOWN("root_manager_unknown", "Unknown"),

    // ---------------------------------------------------------------- CL-UNREADABLE-SMS (appended only)

    /** The wrong message type, or a PDU that the parser could not read. No anomaly placeholder. */
    TITLE_CL_UNREADABLE_SMS("title_cl_unreadable_sms", "Unreadable SMS"),
    EXP_CL_UNREADABLE_SMS(
        "exp_cl_unreadable_sms",
        "Wrong message type. NightJar cannot read this message, so it cannot check it.",
    ),
    REASON_UNREADABLE("reason_unreadable", "NightJar cannot read this message, so it cannot check it."),
    NOTIF_WHAT_UNREADABLE("notif_what_unreadable", "Unreadable message"),
    NOTIF_WHY_UNREADABLE("notif_why_unreadable", "NightJar cannot read it, so it cannot check it."),

    // ---------------------------------------------------------------- Blocking switch off (appended only)

    /** Status title and text when the user turned off the Blocking switch. */
    MSG_ST_BLOCK_OFF("msg_st_block_off", "Blocking off"),
    MSG_ST_BLOCK_OFF_SUB("msg_st_block_off_sub", "NightJar records messages but does not block or hold them."),
}
