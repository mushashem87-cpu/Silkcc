==================================================
FILE 1: MinecraftClientMixin.java
PATH: src/main/java/cc/silk/mixin/MinecraftClientMixin.java
==================================================

package cc.silk.mixin;

import cc.silk.SilkClient;
import cc.silk.event.impl.network.DisconnectEvent;
import cc.silk.event.impl.player.DoAttackEvent;
import cc.silk.event.impl.player.ItemUseEvent;
import cc.silk.utils.IMinecraft;
import cc.silk.event.impl.player.TickEvent;
import cc.silk.event.impl.world.WorldChangeEvent;
import cc.silk.gui.ClickGui;
import cc.silk.module.modules.client.ClickGUIModule;
import cc.silk.profiles.ProfileManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin implements IMinecraft {

    @Shadow
    public ClientWorld world;

    @Shadow
    public HitResult crosshairTarget;

    @Shadow
    public ClientPlayerEntity player;

    @Shadow
    public ClientPlayerInteractionManager interactionManager;

    @Shadow
    @Final
    private RenderTickCounter.Dynamic renderTickCounter;

    @Inject(method = "getWindowTitle", at = @At("HEAD"), cancellable = true)
    public void setTitle(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue("FlareHack 1.21.11");
    }

    @Inject(method = "run", at = @At("HEAD"))
    public void runInject(CallbackInfo ci) {
        if (SilkClient.INSTANCE != null) {
            ProfileManager profileManager = SilkClient.INSTANCE.getProfileManager();
            profileManager.loadProfile("default");
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        if (SilkClient.INSTANCE == null || SilkClient.mc == null) return;

        if (world != null) {
            SilkClient.INSTANCE.getSilkEventBus().post(new TickEvent());
        }

        var optionalClickGuiModule = SilkClient.INSTANCE.getModuleManager().getModule(ClickGUIModule.class);
        if (optionalClickGuiModule.isPresent()) {
            ClickGUIModule clickGuiModule = optionalClickGuiModule.get();
            if (clickGuiModule.isEnabled() && SilkClient.mc.currentScreen == null && world != null) {
                SilkClient.mc.setScreen(new ClickGui());
            } else if (!clickGuiModule.isEnabled() && SilkClient.mc.currentScreen instanceof ClickGui) {
                SilkClient.mc.setScreen(null);
            }
        }
    }

    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    public final void doAttackInject(CallbackInfoReturnable<Boolean> cir) {
        if (SilkClient.INSTANCE == null) return;

        try {
            var antiMissOpt = SilkClient.INSTANCE.getModuleManager().getModule(cc.silk.module.modules.combat.AntiMiss.class);
            if (antiMissOpt.isPresent() && antiMissOpt.get().isEnabled()) {
                if (crosshairTarget == null || crosshairTarget.getType() == HitResult.Type.MISS) {
                    cir.setReturnValue(false);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }

        DoAttackEvent event = new DoAttackEvent();
        SilkClient.INSTANCE.getSilkEventBus().post(event);
    }

    @Inject(method = "stop", at = @At("HEAD"))
    public void stopInject(CallbackInfo ci) {
        if (SilkClient.INSTANCE != null) {
            ProfileManager profileManager = SilkClient.INSTANCE.getProfileManager();
            profileManager.saveProfile("default", true);
        }
    }

    @Inject(method = "setWorld", at = @At("HEAD"))
    public void onWorldChangeInject(ClientWorld newWorld, CallbackInfo ci) {
        if (SilkClient.INSTANCE != null && SilkClient.mc != null) {
            SilkClient.INSTANCE.getSilkEventBus().post(new WorldChangeEvent(newWorld));
        }
    }

    @Inject(method = "onDisconnected", at = @At("HEAD"))
    public final void onDisconnected(CallbackInfo ci) {
        if (SilkClient.INSTANCE == null) return;

        DisconnectEvent event = new DisconnectEvent();
        SilkClient.INSTANCE.getSilkEventBus().post(event);
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    public final void doItemUseInject(CallbackInfo ci) {
        if (SilkClient.INSTANCE == null) return;

        ItemUseEvent event = new ItemUseEvent();
        SilkClient.INSTANCE.getSilkEventBus().post(event);

        if (event.isCancelled()) {
            ci.cancel();
        }
    }
}


==================================================
FILE 2: ClientBrandRetrieverMixin.java (NEW FILE)
PATH: src/main/java/cc/silk/mixin/ClientBrandRetrieverMixin.java
==================================================

package cc.silk.mixin;

import net.minecraft.client.ClientBrandRetriever;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientBrandRetriever.class)
public class ClientBrandRetrieverMixin {

    @Inject(method = "getClientModName", at = @At("HEAD"), cancellable = true, remap = false)
    private static void spoofClientBrand(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue("vanilla");
    }
}


==================================================
FILE 3: Mixins JSON registration
PATH: src/main/resources/<your-mod>.mixins.json
==================================================

Add this entry inside the "client" array:

"ClientBrandRetrieverMixin"

Example:

{
  "client": [
    "MinecraftClientMixin",
    "ClientBrandRetrieverMixin"
  ]
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderInject(boolean tick, CallbackInfo ci) {
        if (SilkClient.INSTANCE == null || SilkClient.mc == null || world == null || player == null) return;
        
        var clickAimModule = SilkClient.INSTANCE.getModuleManager().getModule(cc.silk.module.modules.combat.ClickAimAssist.class);
        if (clickAimModule.isPresent() && clickAimModule.get().isEnabled()) {
            clickAimModule.get().onFrame();
        }
    }

}
