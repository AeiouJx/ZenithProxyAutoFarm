package dev.zenith.autofarm.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.cache.data.inventory.Container;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.properties.api.BlockStateProperties;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import dev.zenith.autofarm.module.AutoFarmModule;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.BlockPosArgument.blockPos;
import static com.zenith.command.brigadier.BlockPosArgument.getBlockPos;
import static com.zenith.command.brigadier.BlockArgument.block;
import static com.zenith.command.brigadier.BlockArgument.getBlock;
import static com.zenith.command.brigadier.ItemArgument.getItem;
import static com.zenith.command.brigadier.ItemArgument.item;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static dev.zenith.autofarm.AutoFarmPlugin.PLUGIN_CONFIG;

public class AutoFarmCommand extends Command {
    private static final Set<String> HARVESTABLE_BLOCKS = Set.of(
        "wheat", "carrots", "potatoes", "beetroots", "nether_wart", "sweet_berry_bush", "cocoa", "pumpkin", "melon", "pitcher_crop", "torchflower_crop"
    );
    private static final Set<String> PLANTABLE_ITEMS = Set.of(
        "wheat_seeds", "carrot", "potato", "beetroot_seeds", "nether_wart", "pumpkin_seeds", "melon_seeds", "pitcher_pod", "torchflower_seeds"
    );
    private static final Set<String> BONEMEALABLE_BLOCKS = Set.of(
        "wheat", "carrots", "potatoes", "beetroots", "sweet_berry_bush", "cocoa", "pumpkin_stem", "melon_stem", "pitcher_crop", "torchflower_crop"
    );

    @Override public CommandUsage commandUsage() {
        return CommandUsage.builder().name("autofarm").category(CommandCategory.MODULE)
            .description("Automatically farms supported crops around the bot. Configure crop lists and toggles here.")
            .usageLines("on/off", "status", "debug [on/off]", "searchRadius <1-8>", "range <1-8>", "batchRange <1-8>", "blocksPerTick <1-16>",
                "waitForInteractTimeout <ticks>", "interactDelay <ticks>", "waitForCropMaturity <ticks>", "storageChest <x y z>", "storageChest clear",
                "harvest on/off", "plant on/off", "onlyReplant on/off", "till on/off", "moistOnly on/off", "bonemeal on/off",
                "harvestBlock add|remove <block>", "plantItem add|remove <item>", "bonemealBlock add|remove <block>")
            .build();
    }

    @Override public LiteralArgumentBuilder<CommandContext> register() {
        return command("autofarm")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.enabled=getToggle(c,"toggle");
                MODULE.get(AutoFarmModule.class).syncEnabledFromConfig();
                return success(c, "AutoFarm " + toggleStrCaps(PLUGIN_CONFIG.enabled));
            }))
            .then(literal("status").executes(c -> {
                return showConfig(c);
            }))
            .then(literal("debug").executes(c -> { return debug(c); })
                .then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.debugMode=getToggle(c,"toggle");
                    return success(c, "AutoFarm Debug " + toggleStrCaps(PLUGIN_CONFIG.debugMode));
                })))
            .then(literal("searchRadius").then(argument("radius", integer(1,8)).executes(c -> {
                return setSearchRadius(c, getInteger(c,"radius"));
            })))
            .then(literal("range").then(argument("range", integer(1,8)).executes(c -> {
                return setSearchRadius(c, getInteger(c,"range"));
            })))
            .then(literal("batchRange").then(argument("range", integer(1,8)).executes(c -> {
                PLUGIN_CONFIG.batchRange=getInteger(c,"range");
                return success(c, "Batch Range Set", PLUGIN_CONFIG.batchRange + " blocks");
            })))
            .then(literal("blocksPerTick").then(argument("count", integer(1,16)).executes(c -> {
                PLUGIN_CONFIG.blocksPerTick=getInteger(c,"count");
                return success(c, "Blocks Per Tick Target Set", PLUGIN_CONFIG.blocksPerTick
                    + " (current ZenithProxy interaction limit: 1 block action per tick)");
            })))
            .then(literal("waitForInteractTimeout").then(argument("ticks", integer(1,1200)).executes(c -> {
                PLUGIN_CONFIG.waitForInteractTimeoutTicks=getInteger(c,"ticks");
                return success(c, "Wait For Interact Timeout Set", PLUGIN_CONFIG.waitForInteractTimeoutTicks + " ticks");
            })))
            .then(literal("interactDelay").then(argument("ticks", integer(1,100)).executes(c -> {
                PLUGIN_CONFIG.interactDelayTicks=getInteger(c,"ticks");
                return success(c, "Interaction Delay Set", PLUGIN_CONFIG.interactDelayTicks + " ticks between inventory actions");
            })))
            .then(literal("waitForCropMaturity").then(argument("ticks", integer(1,72000)).executes(c -> {
                PLUGIN_CONFIG.waitForCropMaturityTicks=getInteger(c,"ticks");
                return success(c, "Crop Maturity Wait Set", PLUGIN_CONFIG.waitForCropMaturityTicks + " ticks between maturity checks");
            })))
            .then(literal("storageChest")
                .then(literal("clear").executes(c -> {
                    PLUGIN_CONFIG.storageChest=BlockPos.ZERO;
                    return success(c, "Storage Chest Cleared", "Automatic chest storage is disabled.");
                }))
                .then(argument("pos", blockPos()).executes(c -> {
                    PLUGIN_CONFIG.storageChest=getBlockPos(c,"pos");
                    return success(c, "Storage Chest Set", PLUGIN_CONFIG.storageChest.toString());
                })))
            .then(toggleSetting("harvest", () -> PLUGIN_CONFIG.harvest, v -> PLUGIN_CONFIG.harvest=v))
            .then(toggleSetting("plant", () -> PLUGIN_CONFIG.plant, v -> PLUGIN_CONFIG.plant=v))
            .then(toggleSetting("onlyReplant", () -> PLUGIN_CONFIG.onlyReplant, v -> PLUGIN_CONFIG.onlyReplant=v))
            .then(toggleSetting("till", () -> PLUGIN_CONFIG.till, v -> PLUGIN_CONFIG.till=v))
            .then(toggleSetting("moistOnly", () -> PLUGIN_CONFIG.moistOnly, v -> PLUGIN_CONFIG.moistOnly=v))
            .then(toggleSetting("bonemeal", () -> PLUGIN_CONFIG.bonemeal, v -> PLUGIN_CONFIG.bonemeal=v))
            .then(blockListSetting("harvestBlock", PLUGIN_CONFIG.harvestBlocks, HARVESTABLE_BLOCKS))
            .then(itemListSetting("plantItem", PLUGIN_CONFIG.plantItems, PLANTABLE_ITEMS))
            .then(blockListSetting("bonemealBlock", PLUGIN_CONFIG.bonemealBlocks, BONEMEALABLE_BLOCKS));
    }

    private LiteralArgumentBuilder<CommandContext> toggleSetting(String name, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return literal(name).then(argument("toggle", toggle()).executes(c -> {
            setter.accept(getToggle(c,"toggle"));
            return success(c, name + " " + toggleStrCaps(getter.get()));
        }));
    }

    private LiteralArgumentBuilder<CommandContext> blockListSetting(String name, Set<String> values, Set<String> allowed) {
        return literal(name)
            .then(literal("add").then(argument("block", block()).executes(c -> {
                Block block=getBlock(c,"block");
                String id=normalize(block.name());
                if (!allowed.contains(id)) return failure(c, "Unsupported Block", id + " is not supported by " + name);
                if (!values.add(id)) return failure(c, "Block Already Configured", id + " is already in " + name);
                return listChanged(c, "Block Added", name, values);
            })))
            .then(literal("remove").then(argument("block", block()).executes(c -> {
                String id=normalize(getBlock(c,"block").name());
                if (!values.remove(id)) return failure(c, "Block Not Configured", id + " was not in " + name);
                return listChanged(c, "Block Removed", name, values);
            })));
    }

    private LiteralArgumentBuilder<CommandContext> itemListSetting(String name, Set<String> values, Set<String> allowed) {
        return literal(name)
            .then(literal("add").then(argument("item", item()).executes(c -> {
                ItemData item=getItem(c,"item");
                String id=normalize(item.name());
                if (!allowed.contains(id)) return failure(c, "Unsupported Planting Item", id + " is registered but not supported for planting");
                if (!values.add(id)) return failure(c, "Planting Item Already Configured", id + " is already in " + name);
                return listChanged(c, "Planting Item Added", name, values, id + " (registry id " + item.id() + ")");
            })))
            .then(literal("remove").then(argument("item", item()).executes(c -> {
                ItemData item=getItem(c,"item");
                String id=normalize(item.name());
                if (!values.remove(id)) return failure(c, "Planting Item Not Configured", id + " was not in " + name);
                return listChanged(c, "Planting Item Removed", name, values, id + " (registry id " + item.id() + ")");
            })));
    }

    private int debug(com.mojang.brigadier.context.CommandContext<CommandContext> context) {
        if (!com.zenith.Proxy.getInstance().isConnected()) return failure(context, "AutoFarm Debug", "Bot is not connected to a server.");
        context.getSource().getData().put("list", true);
        var player=CACHE.getPlayerCache();
        double x=player.getX(), y=player.getY(), z=player.getZ();
        int bx=(int)Math.floor(x), by=(int)Math.floor(y), bz=(int)Math.floor(z);
        StringBuilder out=new StringBuilder();
        out.append(String.format(Locale.ROOT, "Position: x=%.3f y=%.3f z=%.3f\nBlock: %d %d %d\nYaw/Pitch: %.1f / %.1f\n",
            x,y,z,bx,by,bz,player.getYaw(),player.getPitch()));
        out.append("Main hand: ").append(itemDescription(player.getEquipment(EquipmentSlot.MAIN_HAND))).append('\n');
        out.append("Off hand: ").append(itemDescription(player.getEquipment(EquipmentSlot.OFF_HAND))).append('\n');
        out.append("Blocks at feet level y=").append(by).append(':').append('\n');
        appendBlockGrid(out,bx,by,bz);
        out.append("Ground blocks y=").append(by-1).append(':').append('\n');
        appendBlockGrid(out,bx,by-1,bz);
        return success(context, "AutoFarm Debug", out.toString());
    }

    private void appendBlockGrid(StringBuilder out, int bx, int y, int bz) {
        for (int z=bz-1; z<=bz+1; z++) {
            for (int x=bx-1; x<=bx+1; x++) {
                if (!World.isChunkLoadedBlockPos(x,z)) {
                    out.append("[unloaded]");
                    continue;
                }
                var state=World.getBlockState(x,y,z);
                Integer age=state.getProperty(BlockStateProperties.AGE_15);
                out.append('[').append(x).append(',').append(y).append(',').append(z).append(' ')
                    .append(normalize(state.block().name())).append(" stateId=").append(state.id());
                if (age!=null) out.append(" age=").append(age);
                out.append(']');
            }
            out.append('\n');
        }
    }

    private String itemDescription(ItemStack stack) {
        if (stack==null || stack==Container.EMPTY_STACK || stack.getId()==0) return "empty";
        ItemData data=ItemRegistry.REGISTRY.get(stack.getId());
        return (data==null ? "unknown(" + stack.getId() + ")" : normalize(data.name()) + " [id=" + data.id() + "]") + " x" + stack.getAmount();
    }

    @Override public void defaultHandler(CommandContext context) {
        if (!context.getData().containsKey("list")) {
            context.getEmbed()
                .addField("AutoFarm", toggleStr(PLUGIN_CONFIG.enabled))
                .addField("Search Radius", effectiveSearchRadius() + " blocks")
                .addField("Batch Range", PLUGIN_CONFIG.batchRange + " blocks")
                .addField("Blocks Per Tick", PLUGIN_CONFIG.blocksPerTick)
                .addField("Debug", toggleStr(PLUGIN_CONFIG.debugMode))
                .addField("Storage Chest", PLUGIN_CONFIG.storageChest == null || PLUGIN_CONFIG.storageChest.equals(BlockPos.ZERO) ? "not set" : PLUGIN_CONFIG.storageChest.toString())
                .addField("Wait For Interact Timeout", PLUGIN_CONFIG.waitForInteractTimeoutTicks + " ticks")
                .addField("Interaction Delay", PLUGIN_CONFIG.interactDelayTicks + " ticks")
                .addField("Wait For Crop Maturity", PLUGIN_CONFIG.waitForCropMaturityTicks + " ticks")
                .addField("Harvest", toggleStr(PLUGIN_CONFIG.harvest))
                .addField("Harvest Blocks", formatList(PLUGIN_CONFIG.harvestBlocks))
                .addField("Plant", toggleStr(PLUGIN_CONFIG.plant))
                .addField("Plant Items", formatList(PLUGIN_CONFIG.plantItems))
                .addField("Only Replant", toggleStr(PLUGIN_CONFIG.onlyReplant))
                .addField("Till", toggleStr(PLUGIN_CONFIG.till))
                .addField("Moist Only", toggleStr(PLUGIN_CONFIG.moistOnly))
                .addField("Bonemeal", toggleStr(PLUGIN_CONFIG.bonemeal))
                .addField("Bonemeal Blocks", formatList(PLUGIN_CONFIG.bonemealBlocks));
        }
        context.getEmbed().primaryColor();
    }

    private int showConfig(com.mojang.brigadier.context.CommandContext<CommandContext> context) {
        context.getSource().getData().put("list", true);
        return success(context, "AutoFarm Settings", """
            `searchRadius`: %d blocks
            `batchRange`: %d blocks
            `blocksPerTick`: %d
            `debug`: %s
            `storageChest`: %s
            `waitForInteractTimeoutTicks`: %d
            `interactDelayTicks`: %d
            `waitForCropMaturityTicks`: %d
            `harvest`: %s
            `harvestBlocks`: %s
            `plant`: %s
            `plantItems`: %s
            `onlyReplant`: %s
            `till`: %s
            `moistOnly`: %s
            `bonemeal`: %s
            `bonemealBlocks`: %s
            """.formatted(effectiveSearchRadius(), PLUGIN_CONFIG.batchRange, PLUGIN_CONFIG.blocksPerTick, toggleStr(PLUGIN_CONFIG.debugMode),
                PLUGIN_CONFIG.storageChest == null || PLUGIN_CONFIG.storageChest.equals(BlockPos.ZERO) ? "not set" : PLUGIN_CONFIG.storageChest.toString(), PLUGIN_CONFIG.waitForInteractTimeoutTicks, PLUGIN_CONFIG.interactDelayTicks, PLUGIN_CONFIG.waitForCropMaturityTicks,
                toggleStr(PLUGIN_CONFIG.harvest), formatList(PLUGIN_CONFIG.harvestBlocks),
                toggleStr(PLUGIN_CONFIG.plant), formatList(PLUGIN_CONFIG.plantItems), toggleStr(PLUGIN_CONFIG.onlyReplant), toggleStr(PLUGIN_CONFIG.till),
                toggleStr(PLUGIN_CONFIG.moistOnly), toggleStr(PLUGIN_CONFIG.bonemeal), formatList(PLUGIN_CONFIG.bonemealBlocks)));
    }

    private int setSearchRadius(com.mojang.brigadier.context.CommandContext<CommandContext> context, int radius) {
        PLUGIN_CONFIG.searchRadius=radius;
        PLUGIN_CONFIG.range=radius;
        return success(context, "Search Radius Set", radius + " blocks");
    }

    private int effectiveSearchRadius() {
        return PLUGIN_CONFIG.searchRadius == 4 && PLUGIN_CONFIG.range != 4 ? PLUGIN_CONFIG.range : PLUGIN_CONFIG.searchRadius;
    }

    private int listChanged(com.mojang.brigadier.context.CommandContext<CommandContext> context, String title, String name, Set<String> values) {
        return listChanged(context, title, name, values, null);
    }

    private int listChanged(com.mojang.brigadier.context.CommandContext<CommandContext> context, String title, String name, Set<String> values, String itemDetails) {
        context.getSource().getData().put("list", true);
        String detail=(itemDetails == null ? "Updated " + name : "Changed: " + itemDetails);
        return success(context, title, detail + "\n\n" + name + ": " + formatList(values));
    }

    private String formatList(Set<String> values) {
        if (values.isEmpty()) return "none";
        return values.stream().sorted().map(s -> "`" + s + "`").reduce((a,b) -> a + ", " + b).orElse("none");
    }

    private int success(com.mojang.brigadier.context.CommandContext<CommandContext> context, String title) {
        context.getSource().getEmbed().title(title);
        return OK;
    }

    private int success(com.mojang.brigadier.context.CommandContext<CommandContext> context, String title, String description) {
        context.getSource().getData().put("list", true);
        context.getSource().getEmbed().title(title).description(description);
        return OK;
    }

    private int failure(com.mojang.brigadier.context.CommandContext<CommandContext> context, String title, String description) {
        context.getSource().getData().put("list", true);
        context.getSource().getEmbed().title(title).description(description).errorColor();
        return ERROR;
    }

    private String normalize(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }
}
