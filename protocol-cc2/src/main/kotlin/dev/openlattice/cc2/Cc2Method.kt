package dev.openlattice.cc2

/** JSON-RPC method numbers of the Centauri Carbon 2 MQTT API. */
object Cc2Method {
    const val GET_ATTRIBUTES = 1001
    const val GET_STATUS = 1002
    const val START_PRINT = 1020
    const val PAUSE_PRINT = 1021
    const val STOP_PRINT = 1022
    const val RESUME_PRINT = 1023
    const val FEED = 1024
    const val RETRACT = 1025
    const val HOME_AXES = 1026
    const val MOVE_AXES = 1027
    const val SET_TEMPERATURE = 1028
    const val SET_LIGHT = 1029
    const val SET_FAN = 1030
    const val SET_SPEED_MODE = 1031
    const val HISTORY_LIST = 1036
    const val HISTORY_DETAIL = 1037
    const val HISTORY_DELETE = 1038
    const val VIDEO_STREAM_URL = 1042
    const val SET_NAME = 1043
    const val FILE_LIST = 1044
    const val FILE_THUMBNAIL = 1045
    const val FILE_DETAIL = 1046
    const val FILE_DELETE = 1047
    const val DISK_INFO = 1048
    const val EXPORT_TIMELAPSE = 1051
    const val SET_MONO_FILAMENT = 1055
    const val GET_MONO_FILAMENT = 1061
    /** Answers error 1100 on firmware 02.01.01.07; not used. */
    const val GET_AI_DETECTION = 1062
    const val SET_AI_DETECTION = 1063
    const val CANVAS_LOAD = 2001
    const val CANVAS_UNLOAD = 2002
    const val CANVAS_SET_FILAMENT = 2003
    const val SET_AUTO_REFILL = 2004
    const val GET_CANVAS_STATUS = 2005

    const val EVENT_STATUS = 6000
    const val EVENT_ATTRIBUTES = 6008

    /** UDP discovery request method, sent to port [DISCOVERY_PORT]. */
    const val DISCOVER = 7000

    const val MQTT_PORT = 1883
    const val HTTP_PORT = 80
    const val DISCOVERY_PORT = 52700
    const val MQTT_USER = "elegoo"
    const val DEFAULT_ACCESS_CODE = "123456"

    /**
     * Methods this client may send. Anything else is refused locally:
     * unknown methods are untested against real firmware and may misbehave mid-print.
     */
    val ALLOWED: Set<Int> = setOf(
        GET_ATTRIBUTES, GET_STATUS, START_PRINT, PAUSE_PRINT, STOP_PRINT, RESUME_PRINT, FEED, RETRACT,
        HOME_AXES, MOVE_AXES, SET_TEMPERATURE, SET_LIGHT, SET_FAN, SET_SPEED_MODE, HISTORY_LIST,
        HISTORY_DETAIL, HISTORY_DELETE, VIDEO_STREAM_URL, SET_NAME, FILE_LIST, FILE_THUMBNAIL, FILE_DETAIL,
        FILE_DELETE, DISK_INFO, EXPORT_TIMELAPSE, SET_MONO_FILAMENT, GET_MONO_FILAMENT,
        CANVAS_LOAD, CANVAS_UNLOAD, CANVAS_SET_FILAMENT, SET_AUTO_REFILL, GET_CANVAS_STATUS,
    )

    private val ERRORS = mapOf(
        109 to "Filament runout",
        1000 to "Access code rejected",
        1001 to "Unknown interface",
        1002 to "Folder open failed",
        1003 to "Invalid parameter",
        1004 to "File write failed",
        1007 to "File delete failed",
        1009 to "Printer is busy",
        1010 to "Printer is not printing",
        1012 to "Task not found",
        1015 to "Thumbnail does not exist",
        1017 to "USB drive not detected",
        1018 to "USB drive removed",
        1020 to "Timelapse video does not exist",
        1021 to "Print file not found",
        1026 to "Missing bed leveling data",
        9000 to "File offset mismatch",
        9004 to "File verification failed",
    )

    fun errorMessage(code: Int): String = ERRORS[code] ?: "Printer error $code"
}
