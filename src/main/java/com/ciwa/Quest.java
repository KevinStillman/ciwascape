package com.ciwa;

import net.runelite.api.NPC;
import net.runelite.api.events.*;
import net.runelite.client.events.ConfigChanged;

/** Contract for quests. Implement only what you need; default no-ops in BaseQuest below. */
public interface Quest
{
    String id();
    String name();

    /** Should this quest show Inquire on this NPC right now? */
    boolean interestedIn(NPC npc);

    /** Return overlay label for this NPC (or null for none). */
    String overlayLabelFor(NPC npc);

    /** Called when player selects Inquire on an interested NPC. */
    void onInquire(NPC npc);

    default void onStartUp() {}
    default void onShutDown() {}

    default void onGameTick(GameTick e) {}
    default void onChatMessage(ChatMessage e) {}
    default void onNpcSpawned(NpcSpawned e) {}
    default void onNpcDespawned(NpcDespawned e) {}
    default void onConfigChanged(ConfigChanged e) {}
}
