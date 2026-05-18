package com.easyhomework.app.tools

import androidx.annotation.Keep
import app.cash.quickjs.QuickJs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.coroutineContext
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Keep
internal interface JavaScriptHostBridge {
    fun checkStep()
}

class ToolExecutor {

    suspend fun execute(toolCall: ToolCall): ToolResult {
        return withContext(Dispatchers.Default) {
            coroutineContext.ensureActive()
            try {
                when (normalizeToolName(toolCall.name)) {
                    "get_current_datetime" -> executeGetCurrentDateTime(toolCall)
                    "calculate", "evaluate_js", "evaluate_expression" -> executeCalculate(toolCall)
                    "run_javascript" -> executeRunJavaScript(toolCall)
                    "convert_unit" -> executeConvertUnit(toolCall)
                    else -> ToolResult(
                        toolCallId = toolCall.id,
                        content = "Unknown tool: ${toolCall.name}",
                        isError = true,
                    )
                }
            } catch (e: Exception) {
                ToolResult(
                    toolCallId = toolCall.id,
                    content = "Error: ${e.message}",
                    isError = true,
                )
            }
        }
    }

    private fun normalizeToolName(name: String): String {
        return when (name.trim()) {
            "get_current_datatime", "get_datetime" -> "get_current_datetime"
            "run_js", "execute_javascript", "javascript" -> "run_javascript"
            else -> name.trim()
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
            .ifBlank { args.optString("code", "") }
            .stripResultAssignment()

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

    private fun String.stripResultAssignment(): String {
        val trimmed = trim().removeSuffix(";").trim()
        val resultAssignment = Regex("""^(?:const|let|var)?\s*result\s*=\s*(.+)$""")
        return resultAssignment.matchEntire(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
    }

    private fun evaluateExpression(expr: String): Double {
        return ExpressionParser(expr).parse()
    }

    private fun executeRunJavaScript(toolCall: ToolCall): ToolResult {
        val args = parseArgs(toolCall.arguments)
        val code = args.optString("code", "")
            .ifBlank { args.optString("script", "") }
            .ifBlank { args.optString("expression", "") }
        val validationError = validateJavaScriptCode(code)

        return if (validationError != null) {
            ToolResult(
                toolCallId = toolCall.id,
                content = validationError,
                isError = true,
            )
        } else {
            try {
                val result = runJavaScriptWithTimeout(code)
                ToolResult(toolCallId = toolCall.id, content = result)
            } catch (e: Exception) {
                ToolResult(toolCallId = toolCall.id, content = "JavaScript 执行失败: ${e.message}", isError = true)
            }
        }
    }

    private fun validateJavaScriptCode(code: String): String? {
        return when {
            code.isBlank() -> "需要提供 code"
            code.length > MAX_JS_CODE_LENGTH -> "代码过长: ${code.length} 字符，最多 $MAX_JS_CODE_LENGTH 字符"
            containsLoop(code) && !code.contains("checkStep(") -> {
                "代码包含循环时，必须在循环体内调用 checkStep()，例如 for (...) { checkStep(); ... }"
            }
            else -> null
        }
    }

    private fun runJavaScriptWithTimeout(code: String): String {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit<String> {
                evaluateJavaScript(code)
            }
            future.get(JS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IllegalArgumentException("执行超时，最多 ${JS_TIMEOUT_MS}ms。请减少循环次数或改用更直接的算法。", e)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun evaluateJavaScript(code: String): String {
        QuickJs.create().use { quickJs ->
            val bridge = StepLimitBridge(MAX_JS_STEPS)
            quickJs.set("__host", JavaScriptHostBridge::class.java, bridge)
            val result = quickJs.evaluate(buildSandboxedScript(code), "llm-tool.js")
            return formatJavaScriptResult(result)
        }
    }

    private fun containsLoop(code: String): Boolean {
        return Regex("""\b(?:for|while|do)\b""").containsMatchIn(code)
    }

    private fun buildSandboxedScript(code: String): String {
        val escapedCode = JSONObject.quote(code)
        return """
            (function () {
              'use strict';
              const userCode = $escapedCode;
              const SafeFunction = Function;
              const globalRef = Function('return this')();
              const disabled = function (name) {
                return function () { throw new Error(name + ' is disabled in this tool'); };
              };
              globalRef.eval = disabled('eval');
              globalRef.Function = disabled('Function');
              globalRef.Date = undefined;
              globalRef.Math.random = disabled('Math.random');
              globalRef.console = {
                log: function () {},
                warn: function () {},
                error: function () {}
              };
              globalRef.checkStep = function () { __host.checkStep(); };
              const fn = new SafeFunction('checkStep', userCode + '\nreturn typeof result === "undefined" ? undefined : result;');
              try {
                Object.defineProperty(SafeFunction.prototype, 'constructor', { value: undefined });
                Object.defineProperty(globalRef.checkStep, 'constructor', { value: undefined });
              } catch (_) {}
              const value = fn(globalRef.checkStep);
              if (typeof value === 'undefined') return 'undefined';
              if (typeof value === 'bigint') return value.toString() + 'n';
              if (typeof value === 'string') return value;
              if (typeof value === 'number' || typeof value === 'boolean') return String(value);
              try {
                return JSON.stringify(value, null, 2);
              } catch (_) {
                return String(value);
              }
            })();
        """.trimIndent()
    }

    private fun formatJavaScriptResult(value: Any?): String {
        val text = when (value) {
            null -> "undefined"
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> value.toString()
        }.take(MAX_JS_OUTPUT_LENGTH)

        return if (text.length >= MAX_JS_OUTPUT_LENGTH) {
            "$text\n...输出已截断，最多 $MAX_JS_OUTPUT_LENGTH 字符"
        } else {
            text
        }
    }

    private class StepLimitBridge(private val maxSteps: Int) : JavaScriptHostBridge {
        private var steps = 0

        override fun checkStep() {
            steps++
            if (steps > maxSteps) {
                error("执行步数过多，最多 $maxSteps 次 checkStep()")
            }
        }
    }

    private fun factorial(n: Int): Long {
        if (n < 0) throw IllegalArgumentException("负数没有阶乘")
        if (n > 20) throw IllegalArgumentException("阶乘结果过大 (n>20)")
        return if (n <= 1) 1 else n * factorial(n - 1)
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
            "里" to 500.0, "丈" to 3.333, "尺" to 0.3333, "寸" to 0.03333,
        )
        convertWithFactors(value, from, to, length)?.let { return it }

        // Weight (base: kg)
        val weight = mapOf(
            "mg" to 0.000001, "g" to 0.001, "kg" to 1.0, "t" to 1000.0, "ton" to 1000.0, "tons" to 1000.0,
            "oz" to 0.0283495, "ounce" to 0.0283495, "ounces" to 0.0283495,
            "lb" to 0.453592, "lbs" to 0.453592, "pound" to 0.453592, "pounds" to 0.453592,
            "斤" to 0.5, "两" to 0.05,
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
            ("k" to "c") to { v: Double -> v - 273.15 },
        )
        tempConversions[Pair(from, to)]?.let { return it(value) }

        // Area (base: m²)
        val area = mapOf(
            "mm2" to 0.000001, "cm2" to 0.0001, "m2" to 1.0, "km2" to 1000000.0,
            "in2" to 0.00064516, "ft2" to 0.092903, "yd2" to 0.836127,
            "acre" to 4046.86, "acres" to 4046.86,
            "hectare" to 10000.0, "ha" to 10000.0,
            "亩" to 666.667,
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
            "ml" to 0.001, "立方米" to 1000.0,
        )
        convertWithFactors(value, from, to, volume)?.let { return it }

        // Time (base: second)
        val time = mapOf(
            "ms" to 0.001, "s" to 1.0, "sec" to 1.0, "second" to 1.0, "seconds" to 1.0,
            "min" to 60.0, "minute" to 60.0, "minutes" to 60.0,
            "h" to 3600.0, "hr" to 3600.0, "hour" to 3600.0, "hours" to 3600.0,
            "d" to 86400.0, "day" to 86400.0, "days" to 86400.0,
            "w" to 604800.0, "week" to 604800.0, "weeks" to 604800.0,
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
        val normalized = normalizeJsonObject(json)
        return try {
            JSONObject(normalized)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun normalizeJsonObject(json: String): String {
        val trimmed = json.trim()
        if (trimmed.isBlank()) return "{}"
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end >= start) trimmed.substring(start, end + 1) else trimmed
    }

    private companion object {
        const val MAX_JS_CODE_LENGTH = 6_000
        const val MAX_JS_OUTPUT_LENGTH = 4_000
        const val MAX_JS_STEPS = 200_000
        const val JS_TIMEOUT_MS = 1_500L
    }

    private inner class ExpressionParser(expression: String) {
        private val input = expression
            .replace("，", ",")
            .replace("×", "*")
            .replace("÷", "/")
            .replace("π", "pi")
            .filterNot { it.isWhitespace() }
            .lowercase()
        private var pos = 0

        fun parse(): Double {
            val value = parseAddSub()
            if (pos != input.length) {
                throw IllegalArgumentException("Unexpected character '${input[pos]}' at position $pos")
            }
            return value
        }

        private fun parseAddSub(): Double {
            var value = parseMulDiv()
            while (match('+') || match('-')) {
                val op = input[pos - 1]
                val right = parseMulDiv()
                value = if (op == '+') value + right else value - right
            }
            return value
        }

        private fun parseMulDiv(): Double {
            var value = parsePower()
            while (true) {
                val op = when {
                    match('*') -> '*'
                    match('/') -> '/'
                    else -> return value
                }
                val right = parsePower()
                value = if (op == '*') value * right else value / right
            }
        }

        private fun parsePower(): Double {
            val value = parseUnary()
            return if (match("**") || match('^')) value.pow(parsePower()) else value
        }

        private fun parseUnary(): Double {
            return when {
                match('+') -> parseUnary()
                match('-') -> -parseUnary()
                else -> parsePostfix()
            }
        }

        private fun parsePostfix(): Double {
            var value = parsePrimary()
            while (match('!')) {
                value = factorial(value.toInt()).toDouble()
            }
            return value
        }

        private fun parsePrimary(): Double {
            if (match('(')) {
                val value = parseAddSub()
                require(match(')')) { "Missing ')' at position $pos" }
                return value
            }

            if (peek()?.isLetter() == true) {
                val name = readIdentifier()
                if (name == "pi") return PI
                if (name == "e") return E
                require(match('(')) { "Function '$name' requires parentheses" }
                val first = parseAddSub()
                val second = if (match(',')) parseAddSub() else null
                require(match(')')) { "Missing ')' after function '$name'" }
                return applyFunction(name, first, second)
            }

            return readNumber()
        }

        private fun applyFunction(name: String, first: Double, second: Double?): Double {
            return when (name) {
                "sqrt" -> sqrt(first)
                "abs" -> abs(first)
                "sin" -> sin(first)
                "cos" -> cos(first)
                "tan" -> tan(first)
                "asin" -> asin(first)
                "acos" -> acos(first)
                "atan" -> atan(first)
                "log" -> log10(first)
                "ln" -> ln(first)
                "ceil" -> ceil(first)
                "floor" -> floor(first)
                "round" -> round(first)
                "pow" -> first.pow(second ?: throw IllegalArgumentException("pow requires 2 arguments"))
                "max" -> max(first, second ?: throw IllegalArgumentException("max requires 2 arguments"))
                "min" -> min(first, second ?: throw IllegalArgumentException("min requires 2 arguments"))
                else -> throw IllegalArgumentException("Unsupported function '$name'")
            }
        }

        private fun readIdentifier(): String {
            val start = pos
            while (peek()?.isLetter() == true || peek() == '_') pos++
            return input.substring(start, pos)
        }

        private fun readNumber(): Double {
            val start = pos
            while (peek()?.isDigit() == true || peek() == '.') pos++
            if (peek() == 'e') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                while (peek()?.isDigit() == true) pos++
            }
            if (start == pos) throw IllegalArgumentException("Expected number at position $pos")
            return input.substring(start, pos).toDouble()
        }

        private fun match(char: Char): Boolean {
            if (peek() != char) return false
            pos++
            return true
        }

        private fun match(text: String): Boolean {
            if (!input.startsWith(text, pos)) return false
            pos += text.length
            return true
        }

        private fun peek(): Char? = input.getOrNull(pos)
    }
}
