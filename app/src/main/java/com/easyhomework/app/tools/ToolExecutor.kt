package com.easyhomework.app.tools

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

class ToolExecutor {

    suspend fun execute(toolCall: ToolCall): ToolResult {
        return try {
            when (toolCall.name) {
                "get_current_datetime" -> executeGetCurrentDateTime(toolCall)
                "calculate" -> executeCalculate(toolCall)
                "evaluate_js" -> executeEvaluateJs(toolCall)
                "convert_unit" -> executeConvertUnit(toolCall)
                else -> ToolResult(
                    toolCallId = toolCall.id,
                    content = "Unknown tool: ${toolCall.name}",
                    isError = true
                )
            }
        } catch (e: Exception) {
            ToolResult(
                toolCallId = toolCall.id,
                content = "Error: ${e.message}",
                isError = true
            )
        }
    }

    private fun executeGetCurrentDateTime(toolCall: ToolCall): ToolResult {
        val args = parseArgs(toolCall.arguments)
        val timezone = args.optString("timezone", "")

        val tz = if (timezone.isNotBlank()) TimeZone.getTimeZone(timezone) else TimeZone.getDefault()
        val calendar = Calendar.getInstance(tz)
        val now = calendar.time

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = tz }
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply { timeZone = tz }
        val dayFormat = SimpleDateFormat("EEEE", Locale.CHINESE).apply { timeZone = tz }
        val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).apply { timeZone = tz }

        val result = buildString {
            appendLine("日期: ${dateFormat.format(now)}")
            appendLine("时间: ${timeFormat.format(now)}")
            appendLine("星期: ${dayFormat.format(now)}")
            appendLine("完整: ${fullFormat.format(now)}")
            appendLine("时区: ${tz.displayName}")
            appendLine("Unix时间戳(秒): ${now.time / 1000}")
        }

        return ToolResult(toolCallId = toolCall.id, content = result.trim())
    }

    private fun executeCalculate(toolCall: ToolCall): ToolResult {
        val args = parseArgs(toolCall.arguments)
        val expression = args.optString("expression", "")

        if (expression.isBlank()) {
            return ToolResult(toolCallId = toolCall.id, content = "需要提供表达式", isError = true)
        }

        return try {
            val result = evaluateExpression(expression)
            ToolResult(toolCallId = toolCall.id, content = "$expression = $result")
        } catch (e: Exception) {
            ToolResult(toolCallId = toolCall.id, content = "计算失败: ${e.message}", isError = true)
        }
    }

    private fun evaluateExpression(expr: String): Double {
        var s = expr.replace(" ", "").lowercase()
            .replace("pi", PI.toString())
            .replace("π", PI.toString())

        // Factorial: N!
        val factRegex = Regex("""(\d+)!""")
        s = factRegex.replace(s) { factorial(it.groupValues[1].toInt()).toDouble().toString() }

        // Power: a^b or a**b
        val powRegex = Regex("""(-?\d+\.?\d*)\^(-?\d+\.?\d*)""")
        s = powRegex.replace(s) { it.groupValues[1].toDouble().pow(it.groupValues[2].toDouble()).toString() }
        val powRegex2 = Regex("""(-?\d+\.?\d*)\*\*(-?\d+\.?\d*)""")
        s = powRegex2.replace(s) { it.groupValues[1].toDouble().pow(it.groupValues[2].toDouble()).toString() }

        // Math functions
        val funcMap = mapOf(
            "sqrt" to { x: Double -> sqrt(x) },
            "abs" to { x: Double -> abs(x) },
            "sin" to { x: Double -> sin(x) },
            "cos" to { x: Double -> cos(x) },
            "tan" to { x: Double -> tan(x) },
            "asin" to { x: Double -> asin(x) },
            "acos" to { x: Double -> acos(x) },
            "atan" to { x: Double -> atan(x) },
            "log" to { x: Double -> log10(x) },
            "ln" to { x: Double -> ln(x) },
            "ceil" to { x: Double -> ceil(x) },
            "floor" to { x: Double -> floor(x) },
            "round" to { x: Double -> round(x) }
        )
        for ((name, func) in funcMap) {
            val regex = Regex("""$name\(([^)]+)\)""")
            while (regex.containsMatchIn(s)) {
                s = regex.replace(s) { match ->
                    val inner = evaluateExpression(match.groupValues[1]).toString()
                    func(inner.toDouble()).toString()
                }
            }
        }

        return parseAddSub(s, 0).first
    }

    // Recursive descent parser: + -
    private fun parseAddSub(s: String, pos: Int): Pair<Double, Int> {
        var (left, i) = parseMulDiv(s, pos)
        while (i < s.length && (s[i] == '+' || s[i] == '-')) {
            val op = s[i]
            val (right, j) = parseMulDiv(s, i + 1)
            left = if (op == '+') left + right else left - right
            i = j
        }
        return left to i
    }

    // * /
    private fun parseMulDiv(s: String, pos: Int): Pair<Double, Int> {
        var (left, i) = parseUnary(s, pos)
        while (i < s.length && (s[i] == '*' || s[i] == '/')) {
            val op = s[i]
            val (right, j) = parseUnary(s, i + 1)
            left = if (op == '*') left * right else left / right
            i = j
        }
        return left to i
    }

    // Unary +/-
    private fun parseUnary(s: String, pos: Int): Pair<Double, Int> {
        if (pos < s.length && s[pos] == '-') {
            val (v, i) = parsePrimary(s, pos + 1)
            return -v to i
        }
        if (pos < s.length && s[pos] == '+') {
            return parsePrimary(s, pos + 1)
        }
        return parsePrimary(s, pos)
    }

    // Number or parenthesized expression
    private fun parsePrimary(s: String, pos: Int): Pair<Double, Int> {
        if (pos < s.length && s[pos] == '(') {
            val (v, i) = parseAddSub(s, pos + 1)
            val end = if (i < s.length && s[i] == ')') i + 1 else i
            return v to end
        }
        var i = pos
        if (i < s.length && s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        if (i == pos) throw IllegalArgumentException("Unexpected character at position $pos")
        return s.substring(pos, i).toDouble() to i
    }

    private fun factorial(n: Int): Long {
        if (n < 0) throw IllegalArgumentException("负数没有阶乘")
        if (n > 20) throw IllegalArgumentException("阶乘结果过大 (n>20)")
        return if (n <= 1) 1 else n * factorial(n - 1)
    }

    private fun executeEvaluateJs(toolCall: ToolCall): ToolResult {
        val args = parseArgs(toolCall.arguments)
        val code = args.optString("code", "")

        if (code.isBlank()) {
            return ToolResult(toolCallId = toolCall.id, content = "需要提供代码", isError = true)
        }

        return try {
            val result = evaluateExpression(code)
            ToolResult(toolCallId = toolCall.id, content = "计算结果: $result")
        } catch (e: Exception) {
            ToolResult(toolCallId = toolCall.id, content = "执行失败: ${e.message}", isError = true)
        }
    }

    private fun executeConvertUnit(toolCall: ToolCall): ToolResult {
        val args = parseArgs(toolCall.arguments)
        val value = args.optDouble("value", Double.NaN)
        val fromUnit = args.optString("from_unit", "").lowercase().trim()
        val toUnit = args.optString("to_unit", "").lowercase().trim()

        if (value.isNaN() || fromUnit.isBlank() || toUnit.isBlank()) {
            return ToolResult(toolCallId = toolCall.id, content = "需要提供 value, from_unit, to_unit", isError = true)
        }

        return try {
            val result = convertUnit(value, fromUnit, toUnit)
            val formatted = if (result == result.toLong().toDouble()) result.toLong().toString() else "%.6f".format(result).trimEnd('0').trimEnd('.')
            ToolResult(toolCallId = toolCall.id, content = "$value $fromUnit = $formatted $toUnit")
        } catch (e: Exception) {
            ToolResult(toolCallId = toolCall.id, content = "转换失败: ${e.message}", isError = true)
        }
    }

    private fun convertUnit(value: Double, from: String, to: String): Double {
        // Length (base: meter)
        val length = mapOf(
            "mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
            "in" to 0.0254, "inch" to 0.0254, "inches" to 0.0254,
            "ft" to 0.3048, "foot" to 0.3048, "feet" to 0.3048,
            "yd" to 0.9144, "yard" to 0.9144, "yards" to 0.9144,
            "mi" to 1609.344, "mile" to 1609.344, "miles" to 1609.344,
            "里" to 500.0, "丈" to 3.333, "尺" to 0.3333, "寸" to 0.03333
        )
        convertWithFactors(value, from, to, length)?.let { return it }

        // Weight (base: kg)
        val weight = mapOf(
            "mg" to 0.000001, "g" to 0.001, "kg" to 1.0, "t" to 1000.0, "ton" to 1000.0, "tons" to 1000.0,
            "oz" to 0.0283495, "ounce" to 0.0283495, "ounces" to 0.0283495,
            "lb" to 0.453592, "lbs" to 0.453592, "pound" to 0.453592, "pounds" to 0.453592,
            "斤" to 0.5, "两" to 0.05
        )
        convertWithFactors(value, from, to, weight)?.let { return it }

        // Temperature
        val tempConversions: Map<Pair<String, String>, (Double) -> Double> = mapOf(
            ("celsius" to "fahrenheit") to { v: Double -> v * 9.0 / 5 + 32 },
            ("c" to "f") to { v: Double -> v * 9.0 / 5 + 32 },
            ("°c" to "°f") to { v: Double -> v * 9.0 / 5 + 32 },
            ("fahrenheit" to "celsius") to { v: Double -> (v - 32) * 5.0 / 9 },
            ("f" to "c") to { v: Double -> (v - 32) * 5.0 / 9 },
            ("°f" to "°c") to { v: Double -> (v - 32) * 5.0 / 9 },
            ("celsius" to "kelvin") to { v: Double -> v + 273.15 },
            ("c" to "k") to { v: Double -> v + 273.15 },
            ("kelvin" to "celsius") to { v: Double -> v - 273.15 },
            ("k" to "c") to { v: Double -> v - 273.15 }
        )
        tempConversions[Pair(from, to)]?.let { return it(value) }

        // Area (base: m²)
        val area = mapOf(
            "mm2" to 0.000001, "cm2" to 0.0001, "m2" to 1.0, "km2" to 1000000.0,
            "in2" to 0.00064516, "ft2" to 0.092903, "yd2" to 0.836127,
            "acre" to 4046.86, "acres" to 4046.86,
            "hectare" to 10000.0, "ha" to 10000.0,
            "亩" to 666.667
        )
        convertWithFactors(value, from, to, area)?.let { return it }

        // Volume (base: liter)
        val volume = mapOf(
            "ml" to 0.001, "l" to 1.0, "liter" to 1.0, "liters" to 1.0,
            "gal" to 3.78541, "gallon" to 3.78541, "gallons" to 3.78541,
            "qt" to 0.946353, "quart" to 0.946353,
            "pt" to 0.473176, "pint" to 0.473176,
            "cup" to 0.236588, "cups" to 0.236588,
            "fl_oz" to 0.0295735, "floz" to 0.0295735,
            "ml" to 0.001, "立方米" to 1000.0
        )
        convertWithFactors(value, from, to, volume)?.let { return it }

        // Time (base: second)
        val time = mapOf(
            "ms" to 0.001, "s" to 1.0, "sec" to 1.0, "second" to 1.0, "seconds" to 1.0,
            "min" to 60.0, "minute" to 60.0, "minutes" to 60.0,
            "h" to 3600.0, "hr" to 3600.0, "hour" to 3600.0, "hours" to 3600.0,
            "d" to 86400.0, "day" to 86400.0, "days" to 86400.0,
            "w" to 604800.0, "week" to 604800.0, "weeks" to 604800.0
        )
        convertWithFactors(value, from, to, time)?.let { return it }

        throw IllegalArgumentException("不支持从 '$from' 到 '$to' 的转换")
    }

    private fun convertWithFactors(value: Double, from: String, to: String, factors: Map<String, Double>): Double? {
        val fromFactor = factors[from] ?: return null
        val toFactor = factors[to] ?: return null
        return value * fromFactor / toFactor
    }

    private fun parseArgs(json: String): JSONObject {
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            JSONObject()
        }
    }
}
