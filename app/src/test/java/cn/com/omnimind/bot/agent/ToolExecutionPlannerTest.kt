package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.bot.agent.runtime.ToolExecutionPlanner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolExecutionPlannerTest {

    private fun call(id: String, name: String): AssistantToolCall =
        AssistantToolCall(
            id = id,
            function = AssistantToolCallFunction(name = name, arguments = "{}")
        )

    private fun argsFor(calls: List<AssistantToolCall>): Map<String, JsonObject> =
        calls.associate { it.id to buildJsonObject { put("x", JsonPrimitive(1)) } }

    @Test
    fun `parallel safe tools merge into one step`() {
        val calls = listOf(call("1", "file_read"), call("2", "file_list"))
        val steps = ToolExecutionPlanner.plan(calls, argsFor(calls))
        assertEquals(1, steps.size)
        assertEquals(2, steps[0].calls.size)
        assertEquals(listOf("1", "2"), steps[0].calls.map { it.id })
    }

    @Test
    fun `terminal execute is its own step`() {
        val calls = listOf(call("1", "terminal_execute"), call("2", "file_read"))
        val steps = ToolExecutionPlanner.plan(calls, argsFor(calls))
        assertEquals(2, steps.size)
        assertEquals(listOf("1"), steps[0].calls.map { it.id })
        assertEquals(listOf("2"), steps[1].calls.map { it.id })
    }

    @Test
    fun `mixed sequence preserves order and grouping`() {
        val calls = listOf(
            call("1", "file_read"),
            call("2", "terminal_execute"),
            call("3", "file_list"),
            call("4", "file_search"),
        )
        val steps = ToolExecutionPlanner.plan(calls, argsFor(calls))
        assertEquals(3, steps.size)
        assertEquals(listOf("1"), steps[0].calls.map { it.id })
        assertEquals(listOf("2"), steps[1].calls.map { it.id })
        assertEquals(listOf("3", "4"), steps[2].calls.map { it.id })
    }
}
