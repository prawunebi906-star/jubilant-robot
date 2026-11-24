package dev.sellmacro;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * /sellmacro                -> toggle (starts with the last / default item, or stops)
 * /sellmacro sweet_berries  -> start selling that item (any item id, "minecraft:" optional)
 * /sellmacro delay 20       -> ticks to wait between sell cycles (default 20)
 * /sellmacro command sell   -> server command that opens the sell GUI (default "sell")
 *
 * Purely client side: it just types the server's sell command for you, shift-clicks the
 * chosen item into the GUI that opens, and closes the GUI. So it works on any server.
 */
public class SellMacroClient implements ClientModInitializer {

	private enum State { IDLE, WAIT_GUI, FILL }

	private static boolean running = false;
	private static State state = State.IDLE;
	private static Item target = null;
	private static Item lastTarget = null;
	private static String sellCommand = "sell";
	private static int delayTicks = 20;   // pause between cycles
	private static int cooldown = 0;
	private static int waitTimer = 0;
	private static int lastSlot = -1;
	private static int lastCount = -1;
	private static boolean warnedEmpty = false;

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("sellmacro")
				// /sellmacro  (toggle)
				.executes(ctx -> {
					if (running) {
						stop("stopped");
					} else {
						start(lastTarget != null ? lastTarget : defaultItem());
					}
					return 1;
				})
				// /sellmacro delay <ticks>
				.then(ClientCommandManager.literal("delay")
					.then(ClientCommandManager.argument("ticks", IntegerArgumentType.integer(1, 1200))
						.executes(ctx -> {
							delayTicks = IntegerArgumentType.getInteger(ctx, "ticks");
							msg("Delay between sells: " + delayTicks + " ticks");
							return 1;
						})))
				// /sellmacro command <text>
				.then(ClientCommandManager.literal("command")
					.then(ClientCommandManager.argument("cmd", StringArgumentType.greedyString())
						.executes(ctx -> {
							String c = StringArgumentType.getString(ctx, "cmd").trim();
							if (c.startsWith("/")) c = c.substring(1);
							if (c.isEmpty()) c = "sell";
							sellCommand = c;
							msg("Sell command set to /" + sellCommand);
							return 1;
						})))
				// /sellmacro <item>
				.then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
					.suggests((ctx, builder) ->
						SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder))
					.executes(ctx -> {
						String raw = StringArgumentType.getString(ctx, "item").trim();
						Identifier id = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
						Optional<Item> item = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
						if (item.isEmpty()) {
							msg("Unknown item: " + raw);
							return 0;
						}
						if (running) {
							// switch item on the fly
							target = item.get();
							lastTarget = target;
							warnedEmpty = false;
							msg("Now selling " + name(target));
						} else {
							start(item.get());
						}
						return 1;
					}))
			));

		ClientTickEvents.END_CLIENT_TICK.register(SellMacroClient::tick);

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			running = false;
			state = State.IDLE;
		});
	}

	private static Item defaultItem() {
		return BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:sweet_berries"))
			.orElseThrow();
	}

	private static void start(Item item) {
		target = item;
		lastTarget = item;
		running = true;
		state = State.IDLE;
		cooldown = 0;
		warnedEmpty = false;
		msg("Started selling " + name(item) + " via /" + sellCommand + "  (type /sellmacro to stop)");
	}

	private static void stop(String why) {
		running = false;
		state = State.IDLE;
		msg("SellMacro " + why);
	}

	private static String name(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}

	private static void msg(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.literal("[SellMacro] " + text), false);
		}
	}

	private static boolean hasTargetInInventory(LocalPlayer player) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && s.is(target)) return true;
		}
		return false;
	}

	private static void tick(Minecraft mc) {
		if (!running) return;
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null || mc.getConnection() == null) {
			running = false;
			return;
		}

		switch (state) {
			case IDLE -> {
				if (cooldown > 0) { cooldown--; return; }
				if (mc.screen != null) return;              // don't interrupt other GUIs / chat
				if (!hasTargetInInventory(player)) {
					if (!warnedEmpty) {
						msg("No " + name(target) + " in inventory, waiting...");
						warnedEmpty = true;
					}
					return;
				}
				warnedEmpty = false;
				mc.getConnection().sendCommand(sellCommand);
				state = State.WAIT_GUI;
				waitTimer = 60;                              // 3s to receive the GUI
			}

			case WAIT_GUI -> {
				if (mc.screen instanceof AbstractContainerScreen<?> && !(mc.screen instanceof InventoryScreen)) {
					state = State.FILL;
					lastSlot = -1;
					lastCount = -1;
					cooldown = 2;                            // let the server finish syncing the GUI
					return;
				}
				if (--waitTimer <= 0) {
					msg("Sell GUI did not open - check /sellmacro command <cmd>");
					state = State.IDLE;
					cooldown = delayTicks;
				}
			}

			case FILL -> {
				if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
					state = State.IDLE;                      // GUI vanished
					cooldown = delayTicks;
					return;
				}
				if (cooldown > 0) { cooldown--; return; }

				AbstractContainerMenu menu = screen.getMenu();
				Slot found = null;
				for (Slot slot : menu.slots) {
					if (slot.container instanceof Inventory && slot.hasItem() && slot.getItem().is(target)) {
						found = slot;
						break;
					}
				}

				boolean stuck = found != null && found.index == lastSlot && found.getItem().getCount() == lastCount;
				if (found == null || stuck) {
					// nothing left to move (or sell GUI is full) -> close, the server sells on close
					player.closeContainer();
					state = State.IDLE;
					cooldown = delayTicks;
					return;
				}

				lastSlot = found.index;
				lastCount = found.getItem().getCount();
				mc.gameMode.handleInventoryMouseClick(menu.containerId, found.index, 0, ClickType.QUICK_MOVE, player);
				cooldown = 1;                                // one stack every 2 ticks
			}
		}
	}
}
