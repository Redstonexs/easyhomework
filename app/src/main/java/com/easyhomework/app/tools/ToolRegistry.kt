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
            description = """
                获取设备当前真实日期、时间、星期、时区和 Unix 时间戳。
                用户明确询问“今天几月几号”“当前日期是什么”“现在几点”“今天星期几”“当前时间戳”等实时日期时间问题时必须调用。
                当题目必须依赖“现在/今天/当前时间/当前星期/实时时区”才能作答时调用。
                不要用于题干已经给出日期时间、普通历史日期计算、概念解释，或与当前时间无关的问题。
            """.trimIndent(),
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "timezone" to mapOf(
                        "type" to "string",
                        "description" to "仅当用户指定或题目需要其它时区时填写，如 'Asia/Shanghai'、'America/New_York'；否则留空使用本地时区。",
                    ),
                ),
                "required" to emptyList<String>(),
                "additionalProperties" to false,
            ),
        )
    }

    private fun createCalculateTool(): ToolDefinition {
        return ToolDefinition(
            name = "calculate",
            description = """
                执行单个确定数学表达式的精确数值计算，支持基本运算、三角函数、对数、指数、阶乘、幂运算等。
                仅当最终答案依赖精确数值且心算/直接推导容易出错时调用。
                不要用于解题思路、公式推导、概念解释、选择题分析，或可以直接算出的简单小计算；这些情况应直接回答。
            """.trimIndent(),
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "expression" to mapOf(
                        "type" to "string",
                        "description" to "只填写要计算的数学表达式，不要包含解题文字或 result=。如 '2+3*4'、'sin(PI/6)'、'log(100)'、'sqrt(144)'、'15!'、'2^10'。",
                    ),
                ),
                "required" to listOf("expression"),
                "additionalProperties" to false,
            ),
        )
    }

    private fun createConvertUnitTool(): ToolDefinition {
        return ToolDefinition(
            name = "convert_unit",
            description = """
                在同一量纲内进行明确的单位换算，支持长度、重量、温度、面积、体积、时间等常见单位。
                仅当题目明确要求单位转换，且换算结果是作答所必需时调用。
                不要用于没有单位换算需求的问题、题干已给出可直接使用的换算结果，或简单常识换算可以直接写出的情况。
            """.trimIndent(),
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
                "additionalProperties" to false,
            ),
        )
    }

    private fun createRunJavaScriptTool(): ToolDefinition {
        return ToolDefinition(
            name = "run_javascript",
            description = """
                在本地 QuickJS 沙箱中执行同步 JavaScript，仅用于普通计算器无法胜任的复杂数学/算法任务：枚举、递推、动态规划、数组统计、组合搜索、批量验证答案等。
                只有当题目需要多步程序化计算，且 calculate 工具不足以表达时调用。
                不要用于普通解题说明、简单算术、单个表达式计算、联网查询、随机数、读取文件、DOM、定时器或外部库。
                代码必须同步执行，不支持网络、文件、定时器、DOM、随机数或外部库。将最终结果赋值给 result。
                长循环内必须调用 checkStep() 主动检查步数限制。
            """.trimIndent(),
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "code" to mapOf(
                        "type" to "string",
                        "description" to "要执行的同步 JavaScript 代码，最终结果必须赋值给 result。示例：$RUN_JAVASCRIPT_EXAMPLE",
                    ),
                ),
                "required" to listOf("code"),
                "additionalProperties" to false,
            ),
        )
    }

    private const val RUN_JAVASCRIPT_EXAMPLE =
        "let sum=0; for (let i=1;i<=100;i++) { checkStep(); sum+=i*i; } result=sum;"
}
