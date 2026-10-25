package com.shadowplay.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.shadowplay.agent.AgentController;
import com.shadowplay.narration.Narrator;
import com.shadowplay.plan.DebugPlans;
import com.shadowplay.plan.Plan;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Client-side /agent command. Literal subcommands win over the free-text task (Brigadier prefers matching literals). */
public final class AgentCommand {
	private AgentCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("agent")
				.executes(ctx -> {
					help();
					return 1;
				})
				.then(literal("resume").executes(ctx -> {
					AgentController.get().resume();
					return 1;
				}))
				.then(literal("stop").executes(ctx -> {
					AgentController.get().stop();
					return 1;
				}))
				.then(literal("status").executes(ctx -> {
					AgentController.get().status();
					return 1;
				}))
				.then(literal("debug")
						.executes(ctx -> {
							AgentController.get().startPlan(DebugPlans.basic());
							return 1;
						})
						.then(literal("goto").executes(ctx -> {
							AgentController.get().startPlan(DebugPlans.walkOutAndBack());
							return 1;
						}))
						.then(literal("break").executes(ctx -> {
							Plan plan = DebugPlans.breakLookedAt();
							if (plan == null) {
								Narrator.say("Look at a block first.");
							} else {
								AgentController.get().startPlan(plan);
							}
							return 1;
						})))
				.then(literal("help").executes(ctx -> {
					help();
					return 1;
				}))
				.then(argument("task", StringArgumentType.greedyString()).executes(ctx -> {
					AgentController.get().startTask(StringArgumentType.getString(ctx, "task"));
					return 1;
				})));
	}

	private static void help() {
		Narrator.say("/agent <task> - plan and do a task");
		Narrator.say("/agent resume - resume an interrupted task");
		Narrator.say("/agent stop - cancel the current task");
		Narrator.say("/agent status - show state, task and step");
	}
}
