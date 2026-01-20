rootProject.name = "ZUtils"

include(":zutils-plugin")
include("zutils-based-api")
include("nms:NMS_V12111")
findProject(":nms:NMS_V12111")?.name = "NMS_V12111"