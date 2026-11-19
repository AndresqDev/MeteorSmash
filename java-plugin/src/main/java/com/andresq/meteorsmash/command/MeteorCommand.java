package com.andresq.meteorsmash.command;

import java.util.List;

import com.andresq.meteorsmash.MeteorSmashPlugin;
import com.andresq.meteorsmash.visual.MeteorDirector;
import dev.j2kt.pumpkin.abs.AbsCommand;
import pumpkin.Command;
import pumpkin.Player;
import pumpkin.TextComponent;

/** The /meteor command. */
public final class MeteorCommand extends AbsCommand {
    private final MeteorDirector director;

    public MeteorCommand(MeteorDirector director) {
        this.director = director;
    }

    @Override
    public String name() {
        return "meteor";
    }

    @Override
    public String description() {
        return "Summon a cinematic meteor at your crosshair.";
    }

    @Override
    public boolean onCommand(Command.CommandSender sender, String label, List<String> args) {
        if (!sender.hasPermissionLevel(Command.PermissionLevel.THREE)) {
            sender.sendError(TextComponent.text("You do not have permission to summon meteors."));
            return true;
        }
        if (!sender.isPlayer()) {
            sender.sendError(TextComponent.text("Only a player can summon a meteor."));
            return true;
        }

        Player player = sender.asPlayer();
        if (player == null) {
            sender.sendError(TextComponent.text("Player context is unavailable."));
            return true;
        }

        sender.sendMessage(TextComponent.text("Meteor inbound. Clear the impact zone."));
        director.summon(player);
        return true;
    }
}
