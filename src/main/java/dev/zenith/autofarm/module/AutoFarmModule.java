package dev.zenith.autofarm.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.InputRequestFuture;
import com.zenith.feature.player.ClickResult;
import com.zenith.feature.player.RotationHelper;
import com.zenith.feature.player.World;
import com.zenith.feature.player.raycast.RaycastHelper;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.block.properties.api.BlockStateProperties;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenith.event.client.ClientBotTick;
import com.zenith.util.RequestFuture;
import com.zenith.util.timer.Timer;
import com.zenith.util.timer.Timers;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;

import java.util.*;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static dev.zenith.autofarm.AutoFarmPlugin.PLUGIN_CONFIG;

public class AutoFarmModule extends Module {
    private State state = State.ENTRYPOINT;
    private final Timer debugTimer = Timers.tickTimer();
    private final Map<BlockPos, String> replants = new HashMap<>();
    private final Map<BlockPos, Long> plantAttemptTimes = new HashMap<>();
    private final Map<BlockPos, String> cropsWaitingForMaturity = new HashMap<>();
    private final Map<BlockPos, Long> nextMaturityCheckAtMs = new HashMap<>();
    private Set<Integer> pendingPickupItemIds=Set.of();
    private BlockPos pendingPickupOrigin;
    private final List<BlockPos> pendingPickupOrigins=new ArrayList<>();
    private final Deque<BlockPos> batchHarvestQueue=new ArrayDeque<>();
    private final Deque<BlockPos> batchPlantQueue=new ArrayDeque<>();
    private final Map<BlockPos,Integer> batchPlantWaitTicks=new HashMap<>();
    private boolean harvestBatchActive;
    private PathingRequestFuture pickupFuture=PathingRequestFuture.rejected;
    private long pickupStartedAtMs;
    private int pickupAttempts;
    private static final long PLANT_RETRY_DELAY_MS = 30_000L;
    private final Timer waitForInteractTimer = Timers.tickTimer();
    private PathingRequestFuture cropPathingFuture = PathingRequestFuture.rejected;
    private PathingRequestFuture cropActionFuture = PathingRequestFuture.rejected;
    private RequestFuture cropInputFuture = RequestFuture.rejected;
    private InputRequestFuture blockClickFuture=InputRequestFuture.rejected;
    private PathingRequestFuture storagePathFuture = PathingRequestFuture.rejected;
    private InputRequestFuture storageOpenFuture = InputRequestFuture.rejected;
    private RequestFuture storageDepositFuture = RequestFuture.rejected;
    private int storageOpenAttempts;
    private int storageItemCountBeforeDeposit;
    private long nextStorageAttemptAtMs;
    private BlockPos targetBlock;
    private FarmAction targetAction;
    private String targetItem;

    @Override public boolean enabledSetting() { return PLUGIN_CONFIG.enabled; }
    @Override public List<EventConsumer<?>> registerEvents() { return List.of(of(ClientBotTick.class, e -> tick())); }
    @Override public void onDisable() {
        replants.clear();
        plantAttemptTimes.clear();
        cropsWaitingForMaturity.clear();
        nextMaturityCheckAtMs.clear();
        if (state==State.HARVEST_PICKUP_DROPS) BARITONE.getFollowProcess().cancel();
        pendingPickupItemIds=Set.of();
        pendingPickupOrigin=null;
        pendingPickupOrigins.clear();
        batchHarvestQueue.clear();
        batchPlantQueue.clear();
        batchPlantWaitTicks.clear();
        harvestBatchActive=false;
        pickupFuture=PathingRequestFuture.rejected;
        pickupAttempts=0;
        state=State.ENTRYPOINT;
        targetBlock=null;
        targetAction=null;
        targetItem=null;
        storagePathFuture=PathingRequestFuture.rejected;
        storageOpenFuture=InputRequestFuture.rejected;
        storageDepositFuture=RequestFuture.rejected;
        cropPathingFuture=PathingRequestFuture.rejected;
        cropActionFuture=PathingRequestFuture.rejected;
        cropInputFuture=RequestFuture.rejected;
        blockClickFuture=InputRequestFuture.rejected;
        waitForInteractTimer.reset();
    }

    private void tick() {
        if (!CACHE.getPlayerCache().isAlive()) return;
        if (com.zenith.Proxy.getInstance().isInQueue()) {
            resetToEntrypoint();
            return;
        }
        long now=System.currentTimeMillis();
        plantAttemptTimes.entrySet().removeIf(entry -> now-entry.getValue() >= PLANT_RETRY_DELAY_MS * 2);
        if (shouldStoreFarmItems() && !BARITONE.isActive()
            && !harvestBatchActive
            && state!=State.HARVEST_WAIT_FOR_DROPS && state!=State.HARVEST_PICKUP_DROPS
            && CACHE.getPlayerCache().getInventoryCache().getOpenContainer().getContainerId()==0
            && state.ordinal() < State.STORE_GO_TO_CHEST.ordinal()
            && PLUGIN_CONFIG.storageChest != null && !PLUGIN_CONFIG.storageChest.equals(BlockPos.ZERO)
            && now >= nextStorageAttemptAtMs) {
            clearCropTarget();
            setState(State.STORE_GO_TO_CHEST);
        }
        switch (state) {
            case ENTRYPOINT -> setState(State.SEARCH_CROP);
            case SEARCH_PLANTING_BLOCK -> searchPlantingBlock();
            case SEARCH_CROP -> searchCrop();
            case CROP_GO_TO_BLOCK -> goToCrop();
            case CROP_AWAIT_PATHING -> awaitCropPathing();
            case HARVEST_BATCH_NEXT -> harvestBatchNext();
            case PLANT_BATCH_NEXT -> plantBatchNext();
            case CROP_CHECK_BLOCK -> checkCrop();
            case CROP_INTERACT -> interactWithCrop();
            case CROP_AWAIT_TOOL_SELECT -> awaitToolSelection();
            case CROP_ROTATE_TO_TARGET -> rotateToPlantTarget();
            case CROP_AWAIT_ROTATION -> awaitPlantRotation();
            case CROP_PLACE_CROP -> placeCrop();
            case CROP_AWAIT_INTERACTION -> awaitCropInteraction();
            case CROP_AWAIT_PLANT_RESULT -> awaitPlantResult();
            case WAIT_FOR_CROP_MATURITY -> waitForCropMaturity();
            case HARVEST_WAIT_FOR_DROPS -> waitForHarvestDrops();
            case HARVEST_PICKUP_DROPS -> pickupHarvestDrops();
            case STORE_GO_TO_CHEST -> goToStorageChest();
            case STORE_AWAIT_PATHING -> awaitStoragePathing();
            case STORE_OPEN_CHEST -> openStorageChest();
            case STORE_AWAIT_CHEST_OPEN -> awaitStorageChestOpen();
            case STORE_SUBMIT_DEPOSIT -> submitStorageDeposit();
            case STORE_AWAIT_DEPOSIT -> awaitStorageDeposit();
        }
    }

    private void resetToEntrypoint() {
        clearCropTarget();
        harvestBatchActive=false;
        batchHarvestQueue.clear();
        batchPlantQueue.clear();
        batchPlantWaitTicks.clear();
        pendingPickupOrigin=null;
        pendingPickupOrigins.clear();
        pendingPickupItemIds=Set.of();
        state=State.ENTRYPOINT;
        waitForInteractTimer.reset();
    }

    private void searchCrop() {
        if (BARITONE.isActive()) return;
        List<Candidate> candidates=nearbyCandidates();
        if (PLUGIN_CONFIG.debugMode && debugTimer.tick(100)) farmDebug("Searching {} blocks within radius {}", candidates.size(), configuredSearchRadius());
        for (Candidate candidate : candidates) {
            if (inspectCrop(candidate.pos())!=FarmAction.HARVEST) continue;
            beginHarvestBatch(candidate.pos(),candidates);
            setState(State.CROP_GO_TO_BLOCK);
            return;
        }
        for (Candidate candidate : candidates) {
            FarmAction action=inspectCrop(candidate.pos());
            if (action == null || action==FarmAction.HARVEST) continue;
            targetBlock=candidate.pos();
            targetAction=action;
            if (action == FarmAction.PLANT || action == FarmAction.REPLANT) targetItem=plantItemFor(candidate.pos());
            else if (action == FarmAction.BONEMEAL) targetItem="bone_meal";
            else if (action == FarmAction.TILL) targetItem=findHoeName();
            farmDebug("Found {} target {} at [{}, {}, {}]", action, targetItem, targetBlock.x(), targetBlock.y(), targetBlock.z());
            setState(State.CROP_GO_TO_BLOCK);
            return;
        }
        if (!cropsWaitingForMaturity.isEmpty()) setState(State.WAIT_FOR_CROP_MATURITY);
        else if (PLUGIN_CONFIG.plant) setState(State.SEARCH_PLANTING_BLOCK);
    }

    private void beginHarvestBatch(BlockPos anchor,List<Candidate> candidates) {
        harvestBatchActive=true;
        batchHarvestQueue.clear();
        batchPlantQueue.clear();
        batchPlantWaitTicks.clear();
        pendingPickupOrigins.clear();
        pendingPickupItemIds=new HashSet<>();
        pendingPickupOrigin=null;
        for (Candidate candidate : candidates) {
            BlockPos pos=candidate.pos();
            double dx=pos.x()-anchor.x(),dy=pos.y()-anchor.y(),dz=pos.z()-anchor.z();
            int batchRange=Math.max(1,Math.min(8, PLUGIN_CONFIG.batchRange));
            if (dx*dx+dy*dy+dz*dz>batchRange*batchRange) continue;
            if (inspectCrop(pos)==FarmAction.HARVEST) batchHarvestQueue.addLast(pos);
        }
        targetBlock=anchor;
        targetAction=FarmAction.HARVEST;
        targetItem=null;
        farmDebug("Started harvest batch at [{}, {}, {}]: {} mature crops within radius {}",
            anchor.x(),anchor.y(),anchor.z(),batchHarvestQueue.size(),Math.max(1,Math.min(8, PLUGIN_CONFIG.batchRange)));
    }

    private void searchPlantingBlock() {
        if (!PLUGIN_CONFIG.plant || BARITONE.isActive()) { setState(State.SEARCH_CROP); return; }
        List<Candidate> candidates=nearbyCandidates();
        for (Candidate candidate : candidates) {
            BlockPos soilPos=candidate.pos();
            int soilStateId=World.getBlockStateId(soilPos);
            boolean soulSand=isStateOf(soilStateId,BlockRegistry.SOUL_SAND);
            boolean farmland=isStateOf(soilStateId,BlockRegistry.FARMLAND);
            if (!soulSand && !farmland) continue;
            BlockPos cropPos=soilPos.above();
            if (!World.getBlock(cropPos).isAir() || plantCoolingDown(cropPos)) continue;
            String seed=replants.get(cropPos);
            if (seed!=null && !suitableSoil(soilPos,seed)) seed=null;
            if (seed==null && !PLUGIN_CONFIG.onlyReplant) seed=findPlantItem(soilPos);
            if (seed==null) continue;
            int seedId=registryItemId(seed);
            if (InventoryUtil.searchPlayerInventory(stack -> stack.getId()==seedId)<0) continue;
            targetBlock=cropPos;
            targetAction=replants.containsKey(cropPos) ? FarmAction.REPLANT : FarmAction.PLANT;
            targetItem=seed;
            farmDebug("Found planting soil={} target=[{}, {}, {}] item={} inventory=true",soulSand ? "soul_sand" : "farmland",
                cropPos.x(),cropPos.y(),cropPos.z(),seed);
            setState(State.CROP_GO_TO_BLOCK);
            return;
        }
        farmDebug("No plantable soil with an available configured item found in search radius {}",configuredSearchRadius());
        setState(State.SEARCH_CROP);
    }

    private List<Candidate> nearbyCandidates() {
        int px=(int)Math.floor(CACHE.getPlayerCache().getX()), py=(int)Math.floor(CACHE.getPlayerCache().getY()), pz=(int)Math.floor(CACHE.getPlayerCache().getZ());
        int range=configuredSearchRadius();
        List<Candidate> candidates=new ArrayList<>();
        for (int x=px-range;x<=px+range;x++) for (int y=py-range;y<=py+range;y++) for (int z=pz-range;z<=pz+range;z++) {
            if (!World.isChunkLoadedBlockPos(x,z)) continue;
            double dx=x+0.5-CACHE.getPlayerCache().getX(), dy=y+0.5-CACHE.getPlayerCache().getY(), dz=z+0.5-CACHE.getPlayerCache().getZ();
            double d2=dx*dx+dy*dy+dz*dz;
            if (d2<=range*range) candidates.add(new Candidate(new BlockPos(x,y,z),d2));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distanceSquared));
        return candidates;
    }

    private void goToCrop() {
        if (targetBlock == null) { setState(State.SEARCH_CROP); return; }
        // Nether Wart planting needs the bot on top of the soul sand: the
        // reference module paths to the air block directly above its soil.
        // A wider radius lets Baritone stop beside the soil and click its side.
        int goalRangeSq=(targetAction==FarmAction.PLANT || targetAction==FarmAction.REPLANT) ? 0 : 4;
        cropPathingFuture=BARITONE.pathTo(new GoalNear(targetBlock,goalRangeSq));
        cropPathingFuture.addExecutedListener(f -> waitForInteractTimer.reset());
        waitForInteractTimer.reset();
        farmDebug("Pathing to farm target [{}, {}, {}] action={} goalRangeSq={}",
            targetBlock.x(),targetBlock.y(),targetBlock.z(),targetAction,goalRangeSq);
        setState(State.CROP_AWAIT_PATHING);
    }

    private void awaitCropPathing() {
        if (cropPathingFuture.isCompleted()) {
            if (!cropPathingFuture.isAccepted()) {
                warn("AutoFarm could not path near {} at [{}, {}, {}]; skipping this attempt",targetAction,targetBlock.x(),targetBlock.y(),targetBlock.z());
                boolean batchHarvest=harvestBatchActive && targetAction==FarmAction.HARVEST;
                clearCropTarget();
                setState(batchHarvest?State.HARVEST_BATCH_NEXT:harvestBatchActive?State.PLANT_BATCH_NEXT:State.SEARCH_CROP);
                return;
            }
            if (harvestBatchActive && targetAction==FarmAction.HARVEST) {
                setState(State.HARVEST_BATCH_NEXT);
                return;
            }
            setState(State.CROP_CHECK_BLOCK);
        }
    }

    private void harvestBatchNext() {
        while (!batchHarvestQueue.isEmpty()) {
            BlockPos pos=batchHarvestQueue.removeFirst();
            if (inspectCrop(pos)!=FarmAction.HARVEST) continue;
            targetBlock=pos;
            targetAction=FarmAction.HARVEST;
            targetItem=null;
            farmDebug("Harvest batch next: [{}, {}, {}], remaining={}",pos.x(),pos.y(),pos.z(),batchHarvestQueue.size());
            interactWithCrop();
            return;
        }
        farmDebug("Harvest batch complete: harvested candidates processed; {} positions queued for planting, {} drop origins",
            batchPlantQueue.size(),pendingPickupOrigins.size());
        setState(State.PLANT_BATCH_NEXT);
    }

    private void plantBatchNext() {
        if (!PLUGIN_CONFIG.plant) batchPlantQueue.clear();
        BlockPos pos=null;
        String seed=null;
        boolean alreadyInReach=false;
        for (Iterator<BlockPos> iterator=batchPlantQueue.iterator();iterator.hasNext();) {
            BlockPos candidate=iterator.next();
            if (!World.getBlock(candidate).isAir()) continue;
            String candidateSeed=batchPlantItemFor(candidate);
            if (candidateSeed==null || !canPlantFromCurrentPosition(candidate)) continue;
            pos=candidate;
            seed=candidateSeed;
            iterator.remove();
            alreadyInReach=true;
            break;
        }
        while (pos==null && !batchPlantQueue.isEmpty()) {
            BlockPos candidate=batchPlantQueue.removeFirst();
            pos=candidate;
            seed=batchPlantItemFor(candidate);
            if (seed==null) { pos=null; continue; }
        }
        if (pos!=null) {
            if (!World.getBlock(pos).isAir()) {
                int waited=batchPlantWaitTicks.merge(pos,1,Integer::sum);
                if (waited<10) {
                    if (!batchPlantQueue.contains(pos)) batchPlantQueue.addFirst(pos);
                    return;
                }
                warn("AutoFarm could not confirm harvested crop cleared at [{}, {}, {}]; skipping batch replant",pos.x(),pos.y(),pos.z());
                batchPlantWaitTicks.remove(pos);
                setState(State.PLANT_BATCH_NEXT);
                return;
            }
        }
        if (pos!=null) {
            batchPlantWaitTicks.remove(pos);
            targetBlock=pos;
            targetAction=replants.containsKey(pos)?FarmAction.REPLANT:FarmAction.PLANT;
            targetItem=seed;
            farmDebug("Plant batch next: [{}, {}, {}] item={} remaining={} mode={}",
                pos.x(),pos.y(),pos.z(),seed,batchPlantQueue.size(),alreadyInReach?"in-reach":"pathing");
            setState(alreadyInReach?State.CROP_CHECK_BLOCK:State.CROP_GO_TO_BLOCK);
            return;
        }
        if (!pendingPickupOrigins.isEmpty() && !pendingPickupItemIds.isEmpty()) {
            pendingPickupOrigin=pendingPickupOrigins.getFirst();
            farmDebug("Plant batch complete; collecting drops from {} harvested positions",pendingPickupOrigins.size());
            setState(State.HARVEST_WAIT_FOR_DROPS);
        } else {
            farmDebug("Plant batch complete; no matching drops to collect");
            finishHarvestPickup();
        }
    }

    private String batchPlantItemFor(BlockPos pos) {
        String seed=replants.get(pos);
        if (seed!=null && suitableSoil(pos.below(),seed)
            && InventoryUtil.searchPlayerInventory(stack -> stack.getId()==registryItemId(seed))>=0) return seed;
        return PLUGIN_CONFIG.onlyReplant?null:findPlantItem(pos.below());
    }

    private boolean canPlantFromCurrentPosition(BlockPos cropPos) {
        BlockPos soil=cropPos.below();
        var bot=CACHE.getPlayerCache();
        double aimX=soil.x()+0.5,aimY=soil.y()+1.0,aimZ=soil.z()+0.5;
        var rotation=RotationHelper.rotationTo(aimX,aimY,aimZ);
        var ray=RaycastHelper.playerEyeRaycastThroughToBlockTarget(soil.x(),soil.y(),soil.z(),rotation.getX(),rotation.getY());
        if (!ray.hit() || ray.direction()!=com.zenith.mc.block.Direction.UP) return false;
        double dx=ray.intersection().x()-bot.getX();
        double dy=ray.intersection().y()-bot.getEyeY();
        double dz=ray.intersection().z()-bot.getZ();
        return dx*dx+dy*dy+dz*dz<=BOT.getBlockReachDistance()*BOT.getBlockReachDistance();
    }

    private void checkCrop() {
        if (targetBlock == null) { setState(State.SEARCH_CROP); return; }
        FarmAction current=inspectCrop(targetBlock);
        if (current == null) { clearCropTarget(); setState(State.SEARCH_CROP); return; }
        targetAction=current;
        if (current == FarmAction.PLANT || current == FarmAction.REPLANT) targetItem=plantItemFor(targetBlock);
        else if (current == FarmAction.BONEMEAL) targetItem="bone_meal";
        else if (current == FarmAction.TILL) targetItem=findHoeName();
        farmDebug("Checked target: action={} item={} block={} stateId={}", current,targetItem,World.getBlock(targetBlock).name(),World.getBlockStateId(targetBlock));
        setState(State.CROP_INTERACT);
    }

    private void interactWithCrop() {
        if (targetBlock == null || targetAction == null) { setState(State.SEARCH_CROP); return; }
        if (targetAction == FarmAction.HARVEST) {
            Block block=World.getBlock(targetBlock);
            String crop=normalize(block.name());
            if (harvestBatchActive) {
                if (!pendingPickupOrigins.contains(targetBlock)) pendingPickupOrigins.add(targetBlock);
                if (!(pendingPickupItemIds instanceof HashSet<?>)) pendingPickupItemIds=new HashSet<>(pendingPickupItemIds);
                pendingPickupItemIds.addAll(dropItemIdsForCrop(crop));
            } else {
                pendingPickupOrigin=targetBlock;
                pendingPickupOrigins.clear();
                pendingPickupOrigins.add(targetBlock);
                pendingPickupItemIds=dropItemIdsForCrop(crop);
            }
            String seed=isStateOf(World.getBlockStateId(targetBlock),BlockRegistry.NETHER_WART) ? "nether_wart" : seedForCrop(crop);
            if (PLUGIN_CONFIG.onlyReplant && seed!=null) replants.put(targetBlock,seed);
            if (crop.equals("sweet_berry_bush")) {
                blockClickFuture=interact(targetBlock);
                cropInputFuture=blockClickFuture;
            }
            else cropActionFuture=BARITONE.breakBlock(targetBlock.x(),targetBlock.y(),targetBlock.z(),true);
            setState(State.CROP_AWAIT_INTERACTION);
            return;
        }
        if (targetAction == FarmAction.PLANT || targetAction == FarmAction.REPLANT) {
            ItemData item=ItemRegistry.REGISTRY.get(targetItem);
            if (item == null) { clearCropTarget(); setState(State.SEARCH_CROP); return; }
            plantAttemptTimes.put(targetBlock,System.currentTimeMillis());
            ItemStack mainHand=CACHE.getPlayerCache().getEquipment(Hand.MAIN_HAND);
            if (mainHand!=null && mainHand.getId()==item.id()) {
                farmDebug("Plant item already selected in main hand: {} slot={}",targetItem,CACHE.getPlayerCache().getHeldItemSlot());
                setState(State.CROP_ROTATE_TO_TARGET);
                return;
            }
            cropInputFuture=selectInventoryItem(stack -> stack.getId()==item.id());
            setState(State.CROP_AWAIT_TOOL_SELECT);
            return;
        }
        cropInputFuture=selectInventoryItem(stack -> {
            ItemData data=ItemRegistry.REGISTRY.get(stack.getId());
            return data!=null && data.name().equals(targetItem);
        });
        setState(State.CROP_AWAIT_TOOL_SELECT);
    }

    private void awaitToolSelection() {
        if (!cropInputFuture.isCompleted()) return;
        if (!cropInputFuture.isAccepted()) {
            farmDebug("Required tool/item {} was not selected",targetItem);
            clearCropTarget();
            setState(harvestBatchActive?State.PLANT_BATCH_NEXT:State.SEARCH_CROP);
            return;
        }
        farmDebug("Tool selection completed: requested={} selectedHotbarSlot={} mainHand={} offHand={}",
            targetItem,CACHE.getPlayerCache().getHeldItemSlot(),describeStack(CACHE.getPlayerCache().getEquipment(Hand.MAIN_HAND)),
            describeStack(CACHE.getPlayerCache().getEquipment(Hand.OFF_HAND)));
        if (targetAction==FarmAction.PLANT || targetAction==FarmAction.REPLANT) {
            setState(State.CROP_ROTATE_TO_TARGET);
            return;
        }
        blockClickFuture=interact(interactionPosition());
        cropInputFuture=blockClickFuture;
        setState(State.CROP_AWAIT_INTERACTION);
    }

    private void rotateToPlantTarget() {
        if (targetBlock==null || (targetAction!=FarmAction.PLANT && targetAction!=FarmAction.REPLANT)) {
            clearCropTarget();
            setState(harvestBatchActive?State.PLANT_BATCH_NEXT:State.SEARCH_CROP);
            return;
        }
        BlockPos soil=targetBlock.below();
        // Aim at the center of the soil's top face so Nether Wart placement
        // uses Direction.UP and the resulting crop is placed above the soil.
        double aimX=soil.x()+0.5;
        double aimY=soil.y()+1.0;
        double aimZ=soil.z()+0.5;
        var rotation=RotationHelper.rotationTo(aimX,aimY,aimZ);
        farmDebug("Plant rotate request: crop=[{}, {}, {}] soil=[{}, {}, {}] bot=[{}, {}, {}] yaw={} pitch={}",
            targetBlock.x(),targetBlock.y(),targetBlock.z(),soil.x(),soil.y(),soil.z(),
            CACHE.getPlayerCache().getX(),CACHE.getPlayerCache().getY(),CACHE.getPlayerCache().getZ(),rotation.getX(),rotation.getY());
        cropInputFuture=INPUTS.submit(InputRequest.builder().owner(this)
            .priority(com.zenith.feature.pathfinder.Baritone.getPriority()+2)
            .yaw(rotation.getX()).pitch(rotation.getY()).build());
        setState(State.CROP_AWAIT_ROTATION);
    }

    private void awaitPlantRotation() {
        if (cropInputFuture.isCompleted()) {
            if (!cropInputFuture.isAccepted()) {
                warn("AutoFarm could not submit rotation for planting target");
                clearCropTarget();
                setState(harvestBatchActive?State.PLANT_BATCH_NEXT:State.SEARCH_PLANTING_BLOCK);
                return;
            }
            farmDebug("Plant rotation request executed; yaw={} pitch={}",CACHE.getPlayerCache().getYaw(),CACHE.getPlayerCache().getPitch());
            placeCrop();
            return;
        }
        if (waitForInteractTimer.tick(Math.max(1, PLUGIN_CONFIG.waitForInteractTimeoutTicks))) {
            warn("AutoFarm timed out waiting for rotation before planting");
            clearCropTarget();
            setState(harvestBatchActive?State.PLANT_BATCH_NEXT:State.SEARCH_CROP);
        }
    }

    private void placeCrop() {
        if (targetBlock==null || (targetAction!=FarmAction.PLANT && targetAction!=FarmAction.REPLANT)) {
            clearCropTarget();
            setState(harvestBatchActive?State.PLANT_BATCH_NEXT:State.SEARCH_PLANTING_BLOCK);
            return;
        }
        BlockPos soil=targetBlock.below();
        var bot=CACHE.getPlayerCache();
        var ray=RaycastHelper.playerEyeRaycastThroughToBlockTarget(soil.x(),soil.y(),soil.z(),bot.getYaw(),bot.getPitch());
        String rayDescription=ray.hit() && ray.intersection()!=null
            ? "hit="+ray.block().name()+" face="+ray.direction()+" point=["+ray.intersection().x()+", "+ray.intersection().y()+", "+ray.intersection().z()+"]"
            : "miss";
        int soilStateId=World.getBlockStateId(soil);
        int cropStateId=World.getBlockStateId(targetBlock);
        farmDebug("Plant pre-click: targetItem={} heldSlot={} mainHand={} offHand={} soil={} stateId={} crop={} stateId={} ray={} eye=[{}, {}, {}]",
            targetItem,bot.getHeldItemSlot(),describeStack(bot.getEquipment(Hand.MAIN_HAND)),describeStack(bot.getEquipment(Hand.OFF_HAND)),
            World.getBlock(soil).name(),soilStateId,World.getBlock(targetBlock).name(),cropStateId,rayDescription,
            bot.getX(),bot.getEyeY(),bot.getZ());
        logBotFooting();
        farmDebug("Plant click request: item={} soil=[{}, {}, {}] crop=[{}, {}, {}] bot=[{}, {}, {}] yaw={} pitch={}",
            targetItem,soil.x(),soil.y(),soil.z(),targetBlock.x(),targetBlock.y(),targetBlock.z(),
            CACHE.getPlayerCache().getX(),CACHE.getPlayerCache().getY(),CACHE.getPlayerCache().getZ(),
            CACHE.getPlayerCache().getYaw(),CACHE.getPlayerCache().getPitch());
        blockClickFuture=clickAtCurrentRotation(soil);
        cropInputFuture=blockClickFuture;
        setState(State.CROP_AWAIT_INTERACTION);
    }

    private InputRequestFuture clickAtCurrentRotation(BlockPos pos) {
        return INPUTS.submit(InputRequest.builder().owner(this)
            .priority(com.zenith.feature.pathfinder.Baritone.getPriority()+2)
            .input(Input.builder().hand(Hand.MAIN_HAND).rightClick(true)
                .clickTarget(new ClickTarget.BlockPosition(pos.x(),pos.y(),pos.z()))
                .clickRequiresRotation(true).build()).build());
    }

    private BlockPos interactionPosition() {
        return targetAction==FarmAction.TILL || targetAction==FarmAction.PLANT || targetAction==FarmAction.REPLANT
            ? targetBlock.below() : targetBlock;
    }

    private void awaitCropInteraction() {
        RequestFuture future=targetAction==FarmAction.HARVEST && !normalize(World.getBlock(targetBlock).name()).equals("sweet_berry_bush")
            ? cropActionFuture : cropInputFuture;
        if (future.isCompleted()) {
            if (!future.isAccepted()) {
                farmDebug("Crop action was rejected: action={} target=[{}, {}, {}]",targetAction,
                    targetBlock.x(),targetBlock.y(),targetBlock.z());
                boolean batchHarvest=harvestBatchActive && targetAction==FarmAction.HARVEST;
                clearCropTarget();
                setState(batchHarvest?State.HARVEST_BATCH_NEXT:harvestBatchActive?State.PLANT_BATCH_NEXT:State.SEARCH_CROP);
                return;
            }
            boolean baritoneAction=targetAction==FarmAction.HARVEST && !normalize(World.getBlock(targetBlock).name()).equals("sweet_berry_bush");
            if (!baritoneAction && !wasBlockClickSuccessful(interactionPosition())) {
                warn("AutoFarm interaction did not hit expected block [{}, {}, {}]; clickResult={}",
                    interactionPosition().x(),interactionPosition().y(),interactionPosition().z(),blockClickFuture.getClickResult());
                clearCropTarget();
                setState(harvestBatchActive?State.PLANT_BATCH_NEXT:State.SEARCH_CROP);
                return;
            }
            farmDebug("Interaction finished for {} at [{}, {}, {}]",targetAction,targetBlock.x(),targetBlock.y(),targetBlock.z());
            if (targetAction==FarmAction.HARVEST) {
                if (harvestBatchActive) {
                    if (PLUGIN_CONFIG.plant) batchPlantQueue.addLast(targetBlock);
                    farmDebug("Harvest batch action completed at [{}, {}, {}]; replantsQueued={} dropsTracked={}",
                        targetBlock.x(),targetBlock.y(),targetBlock.z(),batchPlantQueue.size(),pendingPickupOrigins.size());
                    clearCropTarget();
                    setState(State.HARVEST_BATCH_NEXT);
                    return;
                }
                clearCropTarget();
                pickupAttempts=0;
                setState(State.HARVEST_WAIT_FOR_DROPS);
                return;
            }
            if (targetAction==FarmAction.PLANT || targetAction==FarmAction.REPLANT) {
                farmDebug("Plant click completed: accepted={} result={} heldSlot={} mainHand={} cropNow={} stateId={}",
                    blockClickFuture.isAccepted(),describeClickResult(blockClickFuture.getClickResult()),
                    CACHE.getPlayerCache().getHeldItemSlot(),describeStack(CACHE.getPlayerCache().getEquipment(Hand.MAIN_HAND)),
                    World.getBlock(targetBlock).name(),World.getBlockStateId(targetBlock));
                setState(State.CROP_AWAIT_PLANT_RESULT);
                awaitPlantResult();
                return;
            }
            clearCropTarget();
            setState(State.SEARCH_PLANTING_BLOCK);
        } else if (waitForInteractTimer.tick(Math.max(1, PLUGIN_CONFIG.waitForInteractTimeoutTicks))) {
            warn("AutoFarm timed out waiting for crop interaction");
            boolean batchHarvest=harvestBatchActive && targetAction==FarmAction.HARVEST;
            clearCropTarget();
            setState(batchHarvest?State.HARVEST_BATCH_NEXT:harvestBatchActive?State.PLANT_BATCH_NEXT:State.SEARCH_CROP);
        }
    }

    private boolean wasBlockClickSuccessful(BlockPos expectedPos) {
        if (!blockClickFuture.isAccepted() || !blockClickFuture.isCompleted()) return false;
        if (!(blockClickFuture.getClickResult() instanceof ClickResult.RightClickResult click)) return false;
        return click.getType()==ClickResult.RightClickResult.RightClickType.USE_ITEM_ON_BLOCK
            && click.getBlockX()==expectedPos.x() && click.getBlockY()==expectedPos.y() && click.getBlockZ()==expectedPos.z();
    }

    private void awaitPlantResult() {
        if (targetBlock==null || targetItem==null) {
            clearCropTarget();
            setState(harvestBatchActive?State.PLANT_BATCH_NEXT:State.SEARCH_CROP);
            return;
        }
        String crop=normalize(World.getBlock(targetBlock).name());
        int stateId=World.getBlockStateId(targetBlock);
        if (isExpectedCropAtTarget(targetItem,crop,stateId)) {
            if (targetAction==FarmAction.REPLANT) replants.remove(targetBlock);
            cropsWaitingForMaturity.put(targetBlock,targetItem);
            nextMaturityCheckAtMs.put(targetBlock,System.currentTimeMillis()+Math.max(1, PLUGIN_CONFIG.waitForCropMaturityTicks)*50L);
            farmDebug("Server confirmed {} at [{}, {}, {}]; queued maturity check",targetItem,targetBlock.x(),targetBlock.y(),targetBlock.z());
            clearCropTarget();
            setState(harvestBatchActive?State.PLANT_BATCH_NEXT:State.SEARCH_CROP);
            return;
        }
        if (waitForInteractTimer.tick(10)) {
            warn("Plant not confirmed: item={} soil={} soilStateId={} cropNow={} cropStateId={} heldSlot={} mainHand={} target=[{}, {}, {}]; retrying after cooldown",
                targetItem,World.getBlock(targetBlock.below()).name(),World.getBlockStateId(targetBlock.below()),crop,stateId,
                CACHE.getPlayerCache().getHeldItemSlot(),describeStack(CACHE.getPlayerCache().getEquipment(Hand.MAIN_HAND)),
                targetBlock.x(),targetBlock.y(),targetBlock.z());
            clearCropTarget();
            setState(harvestBatchActive?State.PLANT_BATCH_NEXT:State.SEARCH_CROP);
        }
    }

    private Set<Integer> dropItemIdsForCrop(String crop) {
        Set<Integer> ids=new HashSet<>();
        switch (crop) {
            case "wheat" -> { addItemId(ids,"wheat"); addItemId(ids,"wheat_seeds"); }
            case "carrots" -> addItemId(ids,"carrot");
            case "potatoes" -> addItemId(ids,"potato");
            case "beetroots" -> { addItemId(ids,"beetroot"); addItemId(ids,"beetroot_seeds"); }
            case "nether_wart" -> addItemId(ids,"nether_wart");
            case "sweet_berry_bush" -> addItemId(ids,"sweet_berries");
            case "cocoa" -> addItemId(ids,"cocoa_beans");
            case "pumpkin" -> addItemId(ids,"pumpkin");
            case "melon" -> addItemId(ids,"melon_slice");
            case "pitcher_crop" -> { addItemId(ids,"pitcher_plant"); addItemId(ids,"pitcher_pod"); }
            case "torchflower_crop" -> { addItemId(ids,"torchflower"); addItemId(ids,"torchflower_seeds"); }
            default -> { }
        }
        return ids;
    }

    private void waitForHarvestDrops() {
        if (pendingPickupOrigin==null || pendingPickupItemIds.isEmpty()) { finishHarvestPickup(); return; }
        if (hasNearbyHarvestDrops()) {
            startHarvestPickup();
        } else if (waitForInteractTimer.tick(10)) {
            farmDebug("No matching harvest drops appeared near [{}, {}, {}]",pendingPickupOrigin.x(),pendingPickupOrigin.y(),pendingPickupOrigin.z());
            finishHarvestPickup();
        }
    }

    private void startHarvestPickup() {
        pickupFuture=BARITONE.getFollowProcess().pickup(stack -> pendingPickupItemIds.contains(stack.getId()));
        pickupStartedAtMs=System.currentTimeMillis();
        pickupAttempts++;
        farmDebug("Collecting {} matching drop types near [{}, {}, {}] (attempt {})",pendingPickupItemIds.size(),
            pendingPickupOrigin.x(),pendingPickupOrigin.y(),pendingPickupOrigin.z(),pickupAttempts);
        setState(State.HARVEST_PICKUP_DROPS);
        waitForInteractTimer.reset();
    }

    private void pickupHarvestDrops() {
        if (!hasNearbyHarvestDrops()) { finishHarvestPickup(); return; }
        if (pickupFuture.isCompleted()) {
            if (!pickupFuture.isAccepted()) {
                warn("AutoFarm pickup request was rejected near harvested block");
                finishHarvestPickup();
                return;
            }
            if (pickupAttempts<3 && waitForInteractTimer.tick(20)) {
                startHarvestPickup();
                return;
            }
        }
        if (System.currentTimeMillis()-pickupStartedAtMs>=10_000L) {
            warn("AutoFarm pickup timed out; continuing with remaining farm targets");
            if (BARITONE.isActive()) BARITONE.getFollowProcess().cancel();
            finishHarvestPickup();
        }
    }

    private boolean hasNearbyHarvestDrops() {
        if (pendingPickupOrigins.isEmpty() && pendingPickupOrigin!=null) pendingPickupOrigins.add(pendingPickupOrigin);
        if (pendingPickupOrigins.isEmpty() || pendingPickupItemIds.isEmpty()) return false;
        for (var entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity.getEntityType()!=EntityType.ITEM) continue;
            var itemStack=entity.getMetadataValue(8,MetadataTypes.ITEM,ItemStack.class);
            if (itemStack==null || !pendingPickupItemIds.contains(itemStack.getId())) continue;
            for (BlockPos origin : pendingPickupOrigins) {
                double dx=entity.getX()-origin.x()-0.5;
                double dy=entity.getY()-origin.y()-0.5;
                double dz=entity.getZ()-origin.z()-0.5;
                if (dx*dx+dy*dy+dz*dz<=36.0) return true;
            }
        }
        return false;
    }

    private void finishHarvestPickup() {
        pendingPickupOrigin=null;
        pendingPickupOrigins.clear();
        pendingPickupItemIds=Set.of();
        pickupFuture=PathingRequestFuture.rejected;
        pickupAttempts=0;
        batchHarvestQueue.clear();
        batchPlantQueue.clear();
        batchPlantWaitTicks.clear();
        harvestBatchActive=false;
        setState(State.SEARCH_CROP);
    }

    private void waitForCropMaturity() {
        long now=System.currentTimeMillis();
        for (var entry : new ArrayList<>(cropsWaitingForMaturity.entrySet())) {
            BlockPos pos=entry.getKey();
            if (now<nextMaturityCheckAtMs.getOrDefault(pos,0L)) continue;
            String seed=entry.getValue();
            String crop=normalize(World.getBlock(pos).name());
            int stateId=World.getBlockStateId(pos);
            Integer age=World.getBlockState(pos).getProperty(BlockStateProperties.AGE_15);
            if (!isExpectedCropAtTarget(seed,crop,stateId)) {
                if (World.getBlock(pos).isAir()) warn("AutoFarm did not observe planted {} at [{}, {}, {}]",seed,pos.x(),pos.y(),pos.z());
                cropsWaitingForMaturity.remove(pos);
                nextMaturityCheckAtMs.remove(pos);
                continue;
            }
            if (mature(crop,age,stateId)) {
                cropsWaitingForMaturity.remove(pos);
                nextMaturityCheckAtMs.remove(pos);
                targetBlock=pos;
                targetAction=FarmAction.HARVEST;
                targetItem=null;
                farmDebug("New crop matured at [{}, {}, {}], returning to harvest",pos.x(),pos.y(),pos.z());
                setState(State.CROP_GO_TO_BLOCK);
                return;
            }
            nextMaturityCheckAtMs.put(pos,now+Math.max(1, PLUGIN_CONFIG.waitForCropMaturityTicks)*50L);
            farmDebug("Crop {} at [{}, {}, {}] is still growing; next check in {} ticks",seed,pos.x(),pos.y(),pos.z(), PLUGIN_CONFIG.waitForCropMaturityTicks);
        }
        if (cropsWaitingForMaturity.isEmpty()) setState(State.SEARCH_PLANTING_BLOCK);
    }

    private boolean isExpectedCropAtTarget(String seed,String crop,int stateId) {
        if (seed==null) return false;
        return switch (seed) {
            case "nether_wart" -> isStateOf(stateId,BlockRegistry.NETHER_WART);
            case "wheat_seeds" -> crop.equals("wheat");
            case "beetroot_seeds" -> crop.equals("beetroots");
            case "carrot" -> crop.equals("carrots");
            case "potato" -> crop.equals("potatoes");
            default -> crop.equals(seed);
        };
    }

    private void setState(State newState) {
        if (state==newState) return;
        farmDebug("State {} -> {}",state,newState);
        state=newState;
        waitForInteractTimer.reset();
    }

    private void clearCropTarget() {
        targetBlock=null; targetAction=null; targetItem=null;
        cropPathingFuture=PathingRequestFuture.rejected;
        cropActionFuture=PathingRequestFuture.rejected;
        cropInputFuture=RequestFuture.rejected;
    }

    private void farmDebug(String message, Object... args) {
        if (PLUGIN_CONFIG.debugMode) info("[debug] " + message,args);
    }

    private void goToStorageChest() {
        if (PLUGIN_CONFIG.storageChest==null || PLUGIN_CONFIG.storageChest.equals(BlockPos.ZERO)) { setState(State.SEARCH_CROP); return; }
        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainer().getContainerId()!=0) return;
        var chest= PLUGIN_CONFIG.storageChest;
        storageOpenAttempts=0;
        farmDebug("Pathing to storage chest [{}, {}, {}]",chest.x(),chest.y(),chest.z());
        storagePathFuture=BARITONE.pathTo(new GoalNear(chest,4));
        storagePathFuture.addExecutedListener(f -> waitForInteractTimer.reset());
        setState(State.STORE_AWAIT_PATHING);
    }

    private void awaitStoragePathing() {
        if (!storagePathFuture.isCompleted()) {
            if (waitForInteractTimer.tick(Math.max(1, PLUGIN_CONFIG.waitForInteractTimeoutTicks)*3)) {
                warn("AutoFarm timed out pathing to storage chest");
                finishStorage(60_000L);
            }
            return;
        }
        if (!storagePathFuture.isAccepted()) {
            warn("AutoFarm could not reach storage chest");
            finishStorage(60_000L);
            return;
        }
        farmDebug("Reached storage chest interaction area at bot [{}, {}, {}]",
            CACHE.getPlayerCache().getX(),CACHE.getPlayerCache().getY(),CACHE.getPlayerCache().getZ());
        setState(State.STORE_OPEN_CHEST);
    }

    private void openStorageChest() {
        int containerId=CACHE.getPlayerCache().getInventoryCache().getOpenContainer().getContainerId();
        if (containerId!=0) {
            setState(State.STORE_SUBMIT_DEPOSIT);
            return;
        }
        if (storageOpenAttempts>=3) {
            warn("AutoFarm failed to open storage chest after {} attempts",storageOpenAttempts);
            finishStorage(60_000L);
            return;
        }
        BlockPos chest= PLUGIN_CONFIG.storageChest;
        if (chest==null || chest.equals(BlockPos.ZERO)) { finishStorage(); return; }
        storageOpenAttempts++;
        farmDebug("Opening storage chest [{}, {}, {}] attempt {} at bot [{}, {}, {}]",
            chest.x(),chest.y(),chest.z(),storageOpenAttempts,CACHE.getPlayerCache().getX(),
            CACHE.getPlayerCache().getY(),CACHE.getPlayerCache().getZ());
        storageOpenFuture=interact(chest);
        setState(State.STORE_AWAIT_CHEST_OPEN);
    }

    private void awaitStorageChestOpen() {
        int containerId=CACHE.getPlayerCache().getInventoryCache().getOpenContainer().getContainerId();
        if (containerId!=0) {
            farmDebug("Storage container opened: id={} type={}",containerId,
                CACHE.getPlayerCache().getInventoryCache().getOpenContainer().getType());
            setState(State.STORE_SUBMIT_DEPOSIT);
            return;
        }
        if (waitForInteractTimer.tick(Math.max(1, PLUGIN_CONFIG.waitForInteractTimeoutTicks))) {
            farmDebug("Storage open wait expired: clickCompleted={} accepted={} result={}",
                storageOpenFuture.isCompleted(),storageOpenFuture.isAccepted(),describeClickResult(storageOpenFuture.getClickResult()));
            INPUTS.submit(InputRequest.builder().owner(this).priority(com.zenith.feature.pathfinder.Baritone.getPriority()+100)
                .input(Input.builder().sneaking(true).build()).build());
            setState(State.STORE_OPEN_CHEST);
        }
    }

    private void submitStorageDeposit() {
        var openContainer=CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        int containerId=openContainer.getContainerId();
        if (containerId==0) {
            warn("AutoFarm storage container closed before deposit submission");
            setState(State.STORE_OPEN_CHEST);
            return;
        }
        Set<Integer> farmItemIds=farmStorageItemIds();
        List<InventoryAction> depositActions=mainInventoryDepositActions(containerId,openContainer,farmItemIds);
        storageItemCountBeforeDeposit=countStorableFarmItems(farmItemIds);
        List<InventoryAction> actions=new ArrayList<>(depositActions);
        actions.add(new CloseContainer(containerId));
        farmDebug("Submitting storage deposit: container={} type={} depositActions={} totalActions={} storableItemCount={}",
            containerId,openContainer.getType(),depositActions.size(),actions.size(),storageItemCountBeforeDeposit);
        if (depositActions.isEmpty()) warn("Storage container opened but no matching farm stacks are eligible for deposit");
        storageDepositFuture=INVENTORY.submit(InventoryActionRequest.builder().owner(this)
            .priority(com.zenith.feature.pathfinder.Baritone.getPriority()+100)
            .actionDelayTicks(Math.max(1, PLUGIN_CONFIG.interactDelayTicks)).actions(actions).build());
        setState(State.STORE_AWAIT_DEPOSIT);
    }

    private void awaitStorageDeposit() {
        if (!storageDepositFuture.isCompleted()) {
            if (waitForInteractTimer.tick(Math.max(1, PLUGIN_CONFIG.waitForInteractTimeoutTicks))) {
                warn("AutoFarm timed out waiting for storage deposit");
                int containerId=CACHE.getPlayerCache().getInventoryCache().getOpenContainer().getContainerId();
                if (containerId!=0) INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                    .priority(com.zenith.feature.pathfinder.Baritone.getPriority()+100)
                    .actions(new CloseContainer(containerId)).build());
                finishStorage(60_000L);
            }
            return;
        }
        farmDebug("Storage deposit completed");
        int remaining=countStorableFarmItems(farmStorageItemIds());
        farmDebug("Storage inventory count: before={} after={}",storageItemCountBeforeDeposit,remaining);
        if (remaining>=storageItemCountBeforeDeposit && shouldStoreFarmItems()) {
            warn("Storage made no inventory progress; pausing retries to avoid reopening the same container");
            finishStorage(60_000L);
        } else if (shouldStoreFarmItems()) setState(State.STORE_GO_TO_CHEST);
        else finishStorage();
    }

    private void finishStorage() {
        finishStorage(5_000L);
    }

    private void finishStorage(long retryDelayMs) {
        storagePathFuture=PathingRequestFuture.rejected;
        storageOpenFuture=InputRequestFuture.rejected;
        storageDepositFuture=RequestFuture.rejected;
        nextStorageAttemptAtMs=System.currentTimeMillis()+Math.max(5_000L,retryDelayMs);
        setState(State.SEARCH_CROP);
    }

    private boolean shouldStoreFarmItems() {
        Set<Integer> farmItemIds=farmStorageItemIds();
        if (farmItemIds.isEmpty()) return false;
        int emptySlots=0;
        boolean hasStorableFarmItems=false;
        var inventory=CACHE.getPlayerCache().getPlayerInventory();
        for (int slot=9;slot<=35;slot++) {
            var stack=inventory.get(slot);
            if (stack==com.zenith.cache.data.inventory.Container.EMPTY_STACK) emptySlots++;
            else if (farmItemIds.contains(stack.getId())) hasStorableFarmItems=true;
        }
        return hasStorableFarmItems && emptySlots<=2;
    }

    private List<InventoryAction> mainInventoryDepositActions(int containerId,
                                                              com.zenith.cache.data.inventory.Container openContainer,
                                                              Set<Integer> farmItemIds) {
        List<InventoryAction> actions=new ArrayList<>();
        int mainInventoryStart=openContainer.getSize()-36;
        int mainInventoryEnd=mainInventoryStart+27;
        for (int containerSlot=mainInventoryStart;containerSlot<mainInventoryEnd;containerSlot++) {
            var stack=openContainer.getItemStack(containerSlot);
            if (stack!=com.zenith.cache.data.inventory.Container.EMPTY_STACK && farmItemIds.contains(stack.getId())) {
                actions.add(new ShiftClick(containerId,containerSlot,ShiftClickItemAction.LEFT_CLICK));
            }
        }
        return actions;
    }

    private int countStorableFarmItems(Set<Integer> farmItemIds) {
        var inventory=CACHE.getPlayerCache().getPlayerInventory();
        int count=0;
        for (int slot=9;slot<=35;slot++) {
            var stack=inventory.get(slot);
            if (stack==com.zenith.cache.data.inventory.Container.EMPTY_STACK || !farmItemIds.contains(stack.getId())) continue;
            count+=stack.getAmount();
        }
        return count;
    }

    private Set<Integer> farmStorageItemIds() {
        Set<Integer> ids=new HashSet<>();
        for (String configuredItem : PLUGIN_CONFIG.plantItems) addItemId(ids, configuredItem);
        for (String crop : PLUGIN_CONFIG.harvestBlocks) {
            switch (crop) {
                case "wheat" -> { addItemId(ids,"wheat"); addItemId(ids,"wheat_seeds"); }
                case "carrots" -> addItemId(ids,"carrot");
                case "potatoes" -> addItemId(ids,"potato");
                case "beetroots" -> { addItemId(ids,"beetroot"); addItemId(ids,"beetroot_seeds"); }
                case "nether_wart" -> addItemId(ids,"nether_wart");
                case "sweet_berry_bush" -> addItemId(ids,"sweet_berries");
                case "cocoa" -> addItemId(ids,"cocoa_beans");
                case "pumpkin" -> addItemId(ids,"pumpkin");
                case "melon" -> addItemId(ids,"melon_slice");
                case "pitcher_crop" -> { addItemId(ids,"pitcher_plant"); addItemId(ids,"pitcher_pod"); }
                case "torchflower_crop" -> { addItemId(ids,"torchflower"); addItemId(ids,"torchflower_seeds"); }
                default -> { }
            }
        }
        return ids;
    }

    private void addItemId(Set<Integer> ids, String itemName) {
        ItemData item=ItemRegistry.REGISTRY.get(itemName);
        if (item!=null) ids.add(item.id());
    }

    private FarmAction inspectCrop(BlockPos pos) {
        Block block=World.getBlock(pos);
        String name=normalize(block.name());
        Integer age=World.getBlockState(pos).getProperty(BlockStateProperties.AGE_15);
        int stateId=World.getBlockStateId(pos);
        boolean configured= PLUGIN_CONFIG.harvestBlocks.contains(name)
            || (isStateOf(stateId, BlockRegistry.NETHER_WART) && PLUGIN_CONFIG.harvestBlocks.contains("nether_wart"));
        if (PLUGIN_CONFIG.harvest && configured && mature(name, age, stateId)) {
            return FarmAction.HARVEST;
        }
        String replantSeed=replants.get(pos);
        if (replantSeed!=null && !block.isAir()) {
            replants.remove(pos);
            replantSeed=null;
        }
        int replantItemId=registryItemId(replantSeed);
        if (PLUGIN_CONFIG.plant && block.isAir() && replantSeed!=null && !plantCoolingDown(pos) && suitableSoil(pos.below(),replantSeed)
            && InventoryUtil.searchPlayerInventory(stack -> stack.getId()==replantItemId)>=0) {
            return FarmAction.REPLANT;
        }
        if (PLUGIN_CONFIG.plant && block.isAir() && !PLUGIN_CONFIG.onlyReplant && !plantCoolingDown(pos) && findPlantItem(pos.below())!=null) {
            return FarmAction.PLANT;
        }
        if (PLUGIN_CONFIG.bonemeal && PLUGIN_CONFIG.bonemealBlocks.contains(name) && age!=null && !mature(name,age,stateId)
            && InventoryUtil.searchPlayerInventory(stack -> stack.getId()==registryItemId("bone_meal"))>=0) {
            return FarmAction.BONEMEAL;
        }
        if (PLUGIN_CONFIG.till && block.isAir() && isTillable(World.getBlock(pos.below())) && (!PLUGIN_CONFIG.moistOnly || waterNearby(pos.below())) && findHoeName()!=null) {
            return FarmAction.TILL;
        }
        return null;
    }

    private String plantItemFor(BlockPos pos) {
        String seed=replants.get(pos);
        if (seed!=null && PLUGIN_CONFIG.plant && suitableSoil(pos.below(),seed)
            && InventoryUtil.searchPlayerInventory(stack -> stack.getId()==registryItemId(seed))>=0) return seed;
        return findPlantItem(pos.below());
    }

    private int registryItemId(String itemName) {
        ItemData item=ItemRegistry.REGISTRY.get(itemName);
        return item==null ? -1 : item.id();
    }

    private String findHoeName() {
        var inventory=CACHE.getPlayerCache().getPlayerInventory();
        for (int i=36;i<=44;i++) {
            var stack=inventory.get(i);
            if (stack==com.zenith.cache.data.inventory.Container.EMPTY_STACK) continue;
            ItemData item=ItemRegistry.REGISTRY.get(stack.getId());
            if (item!=null && item.name().endsWith("_hoe")) return item.name();
        }
        for (int i=9;i<=35;i++) {
            var stack=inventory.get(i);
            if (stack==com.zenith.cache.data.inventory.Container.EMPTY_STACK) continue;
            ItemData item=ItemRegistry.REGISTRY.get(stack.getId());
            if (item!=null && item.name().endsWith("_hoe")) return item.name();
        }
        return null;
    }

    private boolean mature(String name, Integer age, int stateId) {
        if (isStateOf(stateId, BlockRegistry.NETHER_WART)) return stateId == BlockRegistry.NETHER_WART.maxStateId();
        if (name.equals("sweet_berry_bush")) return age!=null && age>=2;
        if (name.equals("cocoa")) return age!=null && age>=2;
        if (age!=null) {
            int max = name.equals("beetroots") ? 3 : name.equals("torchflower_crop") ? 2 : 7;
            return age>=max;
        }
        return name.equals("pumpkin") || name.equals("melon");
    }

    private boolean suitableSoil(BlockPos soilPos, String seed) {
        int soilStateId=World.getBlockStateId(soilPos);
        return seed.equals("nether_wart")
            ? isStateOf(soilStateId, BlockRegistry.SOUL_SAND)
            : isStateOf(soilStateId, BlockRegistry.FARMLAND);
    }
    private boolean plantCoolingDown(BlockPos pos) {
        Long lastAttempt=plantAttemptTimes.get(pos);
        return lastAttempt!=null && System.currentTimeMillis()-lastAttempt < PLANT_RETRY_DELAY_MS;
    }
    private String findPlantItem(BlockPos soilPos) {
        int soilStateId=World.getBlockStateId(soilPos);
        for (String item : PLUGIN_CONFIG.plantItems) {
            boolean correctSoil=item.equals("nether_wart")
                ? isStateOf(soilStateId, BlockRegistry.SOUL_SAND)
                : isStateOf(soilStateId, BlockRegistry.FARMLAND);
            if (!correctSoil) continue;
            ItemData itemData=ItemRegistry.REGISTRY.get(item);
            if (itemData != null && com.zenith.feature.inventory.util.InventoryUtil.searchPlayerInventory(stack -> stack.getId()==itemData.id()) >= 0) return item;
        }
        return null;
    }
    private String seedForCrop(String crop) {
        return switch (crop) {
            case "wheat" -> "wheat_seeds";
            case "beetroots" -> "beetroot_seeds";
            case "carrots" -> "carrot";
            case "potatoes" -> "potato";
            case "nether_wart" -> "nether_wart";
            default -> null;
        };
    }
    private boolean isTillable(Block block) {
        String name=normalize(block.name());
        return name.equals("dirt") || name.equals("grass_block") || name.equals("coarse_dirt") || name.equals("rooted_dirt");
    }
    private boolean isStateOf(int stateId, Block block) {
        return stateId >= block.minStateId() && stateId <= block.maxStateId();
    }
    private int configuredSearchRadius() {
        int configured= PLUGIN_CONFIG.searchRadius == 4 && PLUGIN_CONFIG.range != 4 ? PLUGIN_CONFIG.range : PLUGIN_CONFIG.searchRadius;
        return Math.max(1, Math.min(8, configured));
    }
    private boolean waterNearby(BlockPos pos) {
        for(int x=-4;x<=4;x++) for(int z=-4;z<=4;z++) for(int y=0;y<=1;y++) if(World.isWater(World.getBlockStateId(pos.x()+x,pos.y()+y,pos.z()+z))) return true;
        return false;
    }
    private RequestFuture selectInventoryItem(java.util.function.Predicate<org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack> predicate) {
        int slot=-1;
        var inventory=CACHE.getPlayerCache().getPlayerInventory();
        for(int i=36;i<=44;i++) if(inventory.get(i)!=com.zenith.cache.data.inventory.Container.EMPTY_STACK && predicate.test(inventory.get(i))) { slot=i; break; }
        if(slot<0) for(int i=9;i<=35;i++) if(inventory.get(i)!=com.zenith.cache.data.inventory.Container.EMPTY_STACK && predicate.test(inventory.get(i))) { slot=i; break; }
        if(slot<0) {
            farmDebug("Inventory selection failed: requested={} inventory has no matching stack",targetItem);
            return RequestFuture.rejected;
        }
        var selectedStack=inventory.get(slot);
        var selectedData=ItemRegistry.REGISTRY.get(selectedStack.getId());
        farmDebug("Inventory selection request: requested={} slot={} hotbarSlot={} stack={} count={} currentlyHeldSlot={}",
            targetItem,slot,slot>=36?slot-36:(slot==40?4:6),selectedData==null?selectedStack.getId():selectedData.name(),
            selectedStack.getAmount(),CACHE.getPlayerCache().getHeldItemSlot());
        InventoryActionRequest.Builder request=InventoryActionRequest.builder().owner(this).priority(com.zenith.feature.pathfinder.Baritone.getPriority()+1);
        if(slot>=36) request.actions(new SetHeldItem(slot-36));
        else request.actions(new MoveToHotbarSlot(slot, MoveToHotbarAction.SLOT_6), new SetHeldItem(6));
        return INVENTORY.submit(request.build());
    }
    private InputRequestFuture interact(BlockPos pos) {
        var center=World.blockInteractionCenter(pos.x(),pos.y(),pos.z());
        var rotation=RotationHelper.rotationTo(center.x(),center.y(),center.z());
        farmDebug("Right-click target [{}, {}, {}] from bot [{}, {}, {}] yaw={} pitch={}",
            pos.x(),pos.y(),pos.z(),CACHE.getPlayerCache().getX(),CACHE.getPlayerCache().getY(),CACHE.getPlayerCache().getZ(),rotation.getX(),rotation.getY());
        return INPUTS.submit(InputRequest.builder().owner(this).priority(com.zenith.feature.pathfinder.Baritone.getPriority()+2)
            .yaw(rotation.getX()).pitch(rotation.getY())
            .input(Input.builder().hand(Hand.MAIN_HAND).rightClick(true)
                .clickTarget(new ClickTarget.BlockPosition(pos.x(),pos.y(),pos.z())).clickRequiresRotation(true).build()).build());
    }

    private String describeStack(ItemStack stack) {
        if (stack==null || stack==com.zenith.cache.data.inventory.Container.EMPTY_STACK) return "empty";
        ItemData data=ItemRegistry.REGISTRY.get(stack.getId());
        return (data==null?Integer.toString(stack.getId()):data.name())+"x"+stack.getAmount();
    }

    private String describeClickResult(ClickResult result) {
        if (result instanceof ClickResult.RightClickResult click) {
            return click.getType()+" block=["+click.getBlockX()+", "+click.getBlockY()+", "+click.getBlockZ()+"] name="+
                (click.getBlock()==null?"none":click.getBlock().name());
        }
        return result.getClass().getSimpleName();
    }

    private void logBotFooting() {
        int x=(int)Math.floor(CACHE.getPlayerCache().getX());
        int y=(int)Math.floor(CACHE.getPlayerCache().getY());
        int z=(int)Math.floor(CACHE.getPlayerCache().getZ());
        StringBuilder blocks=new StringBuilder();
        for (int dy=-1;dy<=0;dy++) for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++) {
            if (!blocks.isEmpty()) blocks.append(" | ");
            int bx=x+dx, by=y+dy, bz=z+dz;
            blocks.append('[').append(bx).append(',').append(by).append(',').append(bz).append("] ")
                .append(World.getBlock(bx,by,bz).name()).append('#').append(World.getBlockStateId(bx,by,bz));
        }
        farmDebug("Bot footing blocks: {}",blocks);
    }
    public enum State {
        ENTRYPOINT,
        SEARCH_PLANTING_BLOCK,
        SEARCH_CROP,
        CROP_GO_TO_BLOCK,
        CROP_AWAIT_PATHING,
        HARVEST_BATCH_NEXT,
        PLANT_BATCH_NEXT,
        CROP_CHECK_BLOCK,
        CROP_INTERACT,
        CROP_AWAIT_TOOL_SELECT,
        CROP_ROTATE_TO_TARGET,
        CROP_AWAIT_ROTATION,
        CROP_PLACE_CROP,
        CROP_AWAIT_INTERACTION,
        CROP_AWAIT_PLANT_RESULT,
        WAIT_FOR_CROP_MATURITY,
        HARVEST_WAIT_FOR_DROPS,
        HARVEST_PICKUP_DROPS,
        STORE_GO_TO_CHEST,
        STORE_AWAIT_PATHING,
        STORE_OPEN_CHEST,
        STORE_AWAIT_CHEST_OPEN,
        STORE_SUBMIT_DEPOSIT,
        STORE_AWAIT_DEPOSIT
    }
    private enum FarmAction { HARVEST, PLANT, REPLANT, BONEMEAL, TILL }
    private static String normalize(String name) { return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name; }
    private record Candidate(BlockPos pos, double distanceSquared) {}
}
