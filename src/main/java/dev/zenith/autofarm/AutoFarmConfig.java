package dev.zenith.autofarm;

import java.util.LinkedHashSet;
import java.util.Set;

public class AutoFarmConfig {
    public boolean enabled = false;
    /** Legacy setting retained for existing autofarm.json files and the `range` command. */
    public int range = 4;
    public int searchRadius = 4;
    /** Radius around a chosen crop that belongs to the current harvest/replant batch. */
    public int batchRange = 3;
    /** Maximum block operations requested by the farm batch planner per tick. */
    public int blocksPerTick = 1;
    public boolean debugMode = false;
    public com.zenith.mc.block.BlockPos storageChest = com.zenith.mc.block.BlockPos.ZERO;
    public int waitForInteractTimeoutTicks = 20;
    public int interactDelayTicks = 1;
    /** Poll interval while waiting for a newly planted crop to reach maturity. */
    public int waitForCropMaturityTicks = 200;
    public boolean rotate = true;
    public boolean till = false;
    public boolean moistOnly = true;
    public boolean harvest = true;
    public boolean plant = true;
    public boolean onlyReplant = false;
    public boolean bonemeal = false;
    public Set<String> harvestBlocks = new LinkedHashSet<>(Set.of("wheat", "carrots", "potatoes", "beetroots", "nether_wart", "sweet_berry_bush", "cocoa"));
    public Set<String> plantItems = new LinkedHashSet<>(Set.of("wheat_seeds", "carrot", "potato", "beetroot_seeds", "nether_wart"));
    public Set<String> bonemealBlocks = new LinkedHashSet<>();
}
