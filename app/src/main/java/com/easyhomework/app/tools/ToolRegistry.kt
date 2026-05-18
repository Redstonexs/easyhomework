package com.easyhomework.app.tools

/**
 * Tool definition for LLM function calling.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
) {
    fun toJson(): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to parameters,
            ),
        )
    }
}

/**
 * Tool call from LLM response.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * Tool execution result.
 */
data class ToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false,
)

/**
 * Registry of available tools.
 */
object ToolRegistry {
    val tools = listOf(
        createGetCurrentDateTimeTool(),
        createCalculateTool(),
        createRunJavaScriptTool(),
        createConvertUnitTool(),
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
                        "description" to "时区名称，如 'Asia/Shanghai'、'America/New_York'。默认使用本地时区。",
                    ),
                ),
                "required" to emptyList<String>(),
            ),
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
                        "description" to "数学表达式，如 '2+3*4'、'sin(PI/6)'、'log(100)'、'sqrt(144)'、'15!'、'2^10'",
                    ),
                ),
                "required" to listOf("expression"),
            ),
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
                        "description" to "要转换的数值",
                    ),
                    "from_unit" to mapOf(
                        "type" to "string",
                        "description" to "源单位，如 'km'、'kg'、'celsius'、'm2'",
                    ),
                    "to_unit" to mapOf(
                        "type" to "string",
                        "description" to "目标单位，如 'miles'、'pounds'、'fahrenheit'、'ft2'",
                    ),
                ),
                "required" to listOf("value", "from_unit", "to_unit"),
            ),
        )
    }

    private fun createRunJavaScriptTool(): ToolDefinition {
        return ToolDefinition(
            name = "run_javascript",
            description = """
                在本地 QuickJS 沙箱中执行 JavaScript，用于复杂数学任务：枚举、递推、动态规划、数组统计、组合搜索、验证答案等。
                代码必须同步执行，不支持网络、文件、定时器、DOM、随机数或外部库。将最终结果赋值给 result。
                长循环内可调用 checkStep() 主动检查步数限制。适合需要多步数值计算时使用。
            """.trimIndent(),
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "code" to mapOf(
                        "type" to "string",
                        "description" to "要执行的 JavaScript 代码。示例：$RUN_JAVASCRIPT_EXAMPLE",
                    ),
                ),
                "required" to listOf("code"),
            ),
        )
    }

    private const val RUN_JAVASCRIPT_EXAMPLE =
        "let sum=0; for (let i=1;i<=100;i++) { checkStep(); sum+=i*i; } result=sum;"
}
