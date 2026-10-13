PATH: src/main/java/cc/silk/module/modules/combat/TriggerBot.java

package cc.silk.module.modules.combat;

import cc.silk.event.impl.player.TickEvent;
import cc.silk.event.impl.world.WorldChangeEvent;
import cc.silk.mixin.MinecraftClientAccessor;
import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.modules.misc.Teams;
import cc.silk.module.setting.BooleanSetting;
import cc.silk.module.setting.ModeSetting;
import cc.silk.module.setting.RangeSetting;
import cc.silk.utils.friend.FriendManager;
import cc.silk.utils.mc.CombatUtil;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.SwordItem;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDate;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class TriggerBot extends Module {

    // ---------------- CPS / timing ----------------
    public static final ModeSetting cpsMode = new ModeSetting("CPS Mode", "Cooldown", "CPS", "Cooldown", "Server Cooldown", "Delay");
    public static final RangeSetting cps = new RangeSetting("CPS", 1, 20, 10, 15, 0.5);
    public static final RangeSetting jitter = new RangeSetting("Randomization (ms)", 0, 100, 0, 25, 1);
    public static final RangeSetting cooldownPercent = new RangeSetting("Cooldown %", 50, 100, 95, 100, 1);
    public static final RangeSetting serverCooldownPercent = new RangeSetting("Server Cooldown %", 50, 100, 95, 100, 1);
    public static final RangeSetting delayMs = new RangeSetting("Delay (ms)", 20, 500, 70, 140, 1);
    public static final BooleanSetting dailyDynamics = new BooleanSetting("Daily Dynamics", true);

    // ---------------- Target filters ----------------
    public static final BooleanSetting playersOnly = new BooleanSetting("Players Only", true);
    public static final BooleanSetting mobsEntities = new BooleanSetting("Mobs/Entities", false);
    public static final BooleanSetting crystalsBypass = new BooleanSetting("Crystals Bypass", false);
    public static final BooleanSetting ignorePassiveMobs = new BooleanSetting("No Passive", true);
    public static final BooleanSetting ignoreInvisible = new BooleanSetting("No Invisible", true);

    // ---------------- Condition ----------------
    public static final ModeSetting condition = new ModeSetting("Condition", "Always", "Always", "While Falling Only");

    // ---------------- Misc ----------------
    public static final BooleanSetting respectShields = new BooleanSetting("Ignore Shields", false);
    public static final BooleanSetting useOnlySwordOrAxe = new BooleanSetting("Only Sword or Axe", true);
    public static final BooleanSetting onlyWhenMouseDown = new BooleanSetting("Only Mouse Hold", false);
    public static final BooleanSetting disableOnWorldChange = new BooleanSetting("Disable on Load", false);
    public static final BooleanSetting samePlayer = new BooleanSetting("Same Player", false);

    private final long sessionStart = System.currentTimeMillis();
    private final double dayBias;
    private final double sessionBias;
    private final double driftPhase;

    private long nextAttackAt = 0L;
    private int ticksSinceAttack = 1000;
    private double requiredCooldown = 0.95;
    private double requiredServerPercent = 0.95;
    private Entity target;
    private String lastTargetUUID = null;
    private long samePlayerStamp = 0L;

    public TriggerBot() {
        super("Trigger Bot", "Makes you automatically attack once aimed at a target", -1, Category.COMBAT);

        Random dayRandom = new Random(LocalDate.now().toEpochDay() * 7919L ^ 0x5DEECE66DL);
        this.dayBias = 0.92 + dayRandom.nextDouble() * 0.16;
        this.driftPhase = dayRandom.nextDouble() * Math.PI * 2.0;
        this.sessionBias = 0.96 + ThreadLocalRandom.current().nextDouble() * 0.08;

        addSettings(
                cpsMode, cps, jitter, cooldownPercent, serverCooldownPercent, delayMs, dailyDynamics,
                playersOnly, mobsEntities, crystalsBypass, ignorePassiveMobs, ignoreInvisible,
                condition,
                respectShields, useOnlySwordOrAxe, onlyWhenMouseDown, disableOnWorldChange, samePlayer);
    }

    @EventHandler
    private void onWorldChangeEvent(WorldChangeEvent event) {
        if (disableOnWorldChange.getValue() && this.isEnabled()) {
            this.toggle();
        }
    }

    @EventHandler
    private void tick(TickEvent event) {
        if (isNull()) return;
        assert mc.player != null;

        if (ticksSinceAttack < 1000) ticksSinceAttack++;

        if (mc.player.isUsingItem()) return;
        if (mc.currentScreen != null) return;

        Entity current = mc.targetedEntity;
        if (current == null) return;
        target = current;

        if (!hasTarget(current)) return;

        boolean crystal = current instanceof EndCrystalEntity;

        if (!crystal && !isHoldingAllowedItem()) return;

        if (onlyWhenMouseDown.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            return;
        }

        if (respectShields.getValue() && !crystal) {
            Item item = mc.player.getMainHandStack().getItem();
            if (current instanceof PlayerEntity playerTarget
                    && CombatUtil.isShieldFacingAway(playerTarget)
                    && item instanceof SwordItem) {
                return;
            }
        }

        if (!crystal && !conditionMet()) return;
        if (!samePlayerCheck(current)) return;
        if (!isReady(crystal)) return;

        attack();
        scheduleNext(crystal);
    }

    // ---------------- Readiness per mode ----------------

    private boolean isReady(boolean crystal) {
        long now = System.currentTimeMillis();
        if (now < nextAttackAt) return false;
        if (crystal) return true;

        switch (cpsMode.getMode()) {
            case "CPS":
                return true;
            case "Cooldown":
                return mc.player.getAttackCooldownProgress(0.0f) >= requiredCooldown;
            case "Server Cooldown":
                return serverCooldownReady();
            case "Delay":
                return true;
            default:
                return true;
        }
    }

    // Item cooldowns received from the server are exposed by the ItemCooldownManager.
    // The attack-strength charge itself is computed identically on both sides from
    // the attack speed attribute, so we mirror the server's ticker here.
    private boolean serverCooldownReady() {
        if (mc.player == null) return false;

        if (mc.player.getItemCooldownManager().isCoolingDown(mc.player.getMainHandStack())) {
            return false;
        }

        double attackSpeed = mc.player.getAttributeValue(EntityAttributes.ATTACK_SPEED);
        if (attackSpeed <= 0.0) attackSpeed = 4.0;

        double ticksNeeded = (1.0 / attackSpeed) * 20.0 * requiredServerPercent;
        return (ticksSinceAttack + 0.5) >= ticksNeeded;
    }

    private void scheduleNext(boolean crystal) {
        long now = System.currentTimeMillis();
        double interval;

        if (crystal) {
            interval = cpsInterval();
        } else {
            switch (cpsMode.getMode()) {
                case "CPS":
                    interval = cpsInterval();
                    break;
                case "Delay":
                    interval = delayInterval();
                    break;
                default:
                    interval = jitterValue();
                    break;
            }
        }

        nextAttackAt = now + (long) Math.max(0.0, interval);
        ticksSinceAttack = 0;
        requiredCooldown = rand(cooldownPercent.getMinValue(), cooldownPercent.getMaxValue()) / 100.0;
        requiredServerPercent = rand(serverCooldownPercent.getMinValue(), serverCooldownPercent.getMaxValue()) / 100.0;
    }

    private double cpsInterval() {
        double rate = rand(cps.getMinValue(), cps.getMaxValue());
        if (rate < 1.0) rate = 1.0;
        return (1000.0 / rate) + jitterValue();
    }

    private double delayInterval() {
        double base = rand(delayMs.getMinValue(), delayMs.getMaxValue());

        if (dailyDynamics.getValue()) {
            double minutes = (System.currentTimeMillis() - sessionStart) / 60000.0;
            double drift = 1.0 + 0.05 * Math.sin(minutes * (Math.PI * 2.0 / 17.0) + driftPhase);
            base *= dayBias * sessionBias * drift;
            base += ThreadLocalRandom.current().nextGaussian() * (base * 0.05);
        }

        return Math.max(20.0, base) + jitterValue();
    }

    private double jitterValue() {
        return rand(jitter.getMinValue(), jitter.getMaxValue());
    }

    private double rand(double min, double max) {
        if (max <= min) return min;
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    // ---------------- Condition ----------------

    private boolean conditionMet() {
        if (mc.player == null) return false;
        if (!condition.getMode().equals("While Falling Only")) return true;

        return !mc.player.isOnGround()
                && !mc.player.isClimbing()
                && !mc.player.isTouchingWater()
                && !mc.player.isInLava()
                && mc.player.getVehicle() == null
                && !mc.player.hasStatusEffect(StatusEffects.LEVITATION)
                && !mc.player.hasStatusEffect(StatusEffects.SLOW_FALLING)
                && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS)
                && mc.player.fallDistance > 0
                && mc.player.getVelocity().y < 0;
    }

    // ---------------- Helpers ----------------

    private boolean samePlayerCheck(Entity entity) {
        if (!samePlayer.getValue()) return true;
        if (entity == null) return false;

        long now = System.currentTimeMillis();
        if (lastTargetUUID == null || now - samePlayerStamp >= 3000L) {
            lastTargetUUID = entity.getUuidAsString();
            samePlayerStamp = now;
            return true;
        }
        return entity.getUuidAsString().equals(lastTargetUUID);
    }

    private boolean isHoldingAllowedItem() {
        if (!useOnlySwordOrAxe.getValue()) return true;
        if (mc.player == null) return false;

        Item item = mc.player.getMainHandStack().getItem();
        return item instanceof AxeItem || item instanceof SwordItem;
    }

    public void attack() {
        ((MinecraftClientAccessor) mc).invokeDoAttack();

        if (samePlayer.getValue() && target != null) {
            lastTargetUUID = target.getUuidAsString();
            samePlayerStamp = System.currentTimeMillis();
        }
    }

    public boolean hasTarget(Entity en) {
        if (en == null) return false;
        if (en == mc.player || en == mc.cameraEntity || !en.isAlive()) return false;

        if (en instanceof EndCrystalEntity) return crystalsBypass.getValue();
        if (en instanceof WindChargeEntity) return false;

        if (en instanceof PlayerEntity player) {
            if (FriendManager.isFriend(player.getUuid())) return false;
            if (Teams.isTeammate(en)) return false;
            return !ignoreInvisible.getValue() || !en.isInvisible();
        }

        if (playersOnly.getValue() || !mobsEntities.getValue()) return false;
        if (!(en instanceof LivingEntity) || en instanceof ArmorStandEntity) return false;
        if (en instanceof Tameable) return false;
        if (en instanceof PassiveEntity && ignorePassiveMobs.getValue()) return false;
        if (Teams.isTeammate(en)) return false;

        return !ignoreInvisible.getValue() || !en.isInvisible();
    }

    @Override
    public void onEnable() {
        nextAttackAt = 0L;
        ticksSinceAttack = 1000;
        lastTargetUUID = null;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        nextAttackAt = 0L;
        ticksSinceAttack = 1000;
        lastTargetUUID = null;
        super.onDisable();
    }
}
