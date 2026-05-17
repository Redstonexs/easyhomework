package com.easyhomework.app.tools

/**
 * Tool definition for LLM function calling.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
) {
    fun toJson(): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to parameters
            )
        )
    }
}

/**
 * Tool call from LLM response.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * Tool execution result.
 */
data class ToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false
)

/**
 * Registry of available tools.
 */
object ToolRegistry {
    val tools = listOf(
        createGetCurrentDateTimeTool(),
        createCalculateTool(),
        createConvertUnitTool()
    )

    fun getToolDefinitions(): List<ToolDefinition> = tools

    fun getToolDefinition(name: String): ToolDefinition? = tools.find { it.name == name }

    private fun createGetCurrentDateTimeTool(): ToolDefinition {
        return ToolDefinition(
            name = "get_current_datetime",
            description = "获取当前的日期和时间信息。当题目涉及日期、时间、星期、时区等相关问题时使用此工具。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "timezone" to mapOf(
                        "type" to "string",
                        "description" to "时区名称，如 'Asia/Shanghai'、'America/New_York'。默认使用本地时区。"
                    )
                ),
                "required" to emptyList<String>()
            )
        )
    }

    private fun createCalculateTool(): ToolDefinition {
        return ToolDefinition(
            name = "calculate",
            description = "执行数学计算。支持基本运算、三角函数、对数、指数、阶乘、幂运算等。当需要精确计算数值结果时使用此工具。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "expression" to mapOf(
                        "type" to "string",
                        "description" to "数学表达式，如 '2+3*4'、'sin(PI/6)'、'log(100)'、'sqrt(144)'、'15!'、'2^10'"
                    )
                ),
                "required" to listOf("expression")
            )
        )
    }

    private fun createConvertUnitTool(): ToolDefinition {
        return ToolDefinition(
            name = "convert_unit",
            description = "进行单位转换。支持长度、重量、温度、面积、体积、时间等常见单位之间的转换。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "value" to mapOf(
                        "type" to "number",
                        "description" to "要转换的数值"
                    ),
                    "from_unit" to mapOf(
                        "type" to "string",
                        "description" to "源单位，如 'km'、'kg'、'celsius'、'm2'"
                    ),
                    "to_unit" to mapOf(
                        "type" to "string",
                        "description" to "目标单位，如 'miles'、'pounds'、'fahrenheit'、'ft2'"
                    )
                ),
                "required" to listOf("value", "from_unit", "to_unit")
            )
        )
    }
}
