package com.andresq.meteorsmash;

import java.util.List;

import com.andresq.meteorsmash.command.MeteorCommand;
import com.andresq.meteorsmash.visual.MeteorDirector;
import dev.j2kt.pumpkin.abs.PumpkinAbsPlugin;
import pumpkin.PluginMetadata;

/**
 * MeteorSmash entry point.
 *
 * The plugin keeps its whole animation in memory and drives it from one tick
 * task. There are no threads or async continuations in the gameplay path.
 */
public final class MeteorSmashPlugin extends PumpkinAbsPlugin {
    private MeteorDirector director;

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata(
                "meteorsmash",
                "0.1.8",
                List.of("Andresq"),
                "Cinematic block-display meteor with a burning impact crater.",
                List.of(),
                List.of("meteorsmash:command.meteor"));
    }

    @Override
    protected void onEnable() {
        director = new MeteorDirector(this);
        registerCommand(new MeteorCommand(director));
    }

    @Override
    protected void onDisable() {
        if (director != null) {
            director.cancelAll();
        }
    }
}
