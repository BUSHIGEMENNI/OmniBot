package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.tool.AgentToolConcurrencyPolicy
import cn.com.omnimind.bot.agent.tool.ToolConcurrency
import kotlinx.serialization.json.JsonObject

data class ToolExecutionStep(
    val calls: List<AssistantToolCall>,
    val parallelGroup: Int,
)

object ToolExecutionPlanner {

    fun plan(
        calls: List<AssistantToolCall>,
        parsedArgs: Map<String, JsonObject>,
    ): List<ToolExecutionStep> {
        if (calls.isEmpty()) return emptyList()
        val steps = mutableListOf<ToolExecutionStep>()
        val current = mutableListOf<AssistantToolCall>()
        var currentParallel = false
        var groupCounter = 0
        for (call in calls) {
            val args = parsedArgs[call.id] ?: JsonObject(emptyMap())
            val isParallel = AgentToolConcurrencyPolicy.classify(call.function.name, args) ==
                ToolConcurrency.PARALLEL_SAFE
            if (current.isEmpty()) {
                current.add(call)
                currentParallel = isParallel
            } else if (isParallel && currentParallel) {
                current.add(call)
            } else {
                groupCounter += 1
                steps.add(ToolExecutionStep(current.toList(), groupCounter))
                current.clear()
                current.add(call)
                currentParallel = isParallel
            }
        }
        groupCounter += 1
        steps.add(ToolExecutionStep(current.toList(), groupCounter))
        return steps
    }
}
