package com.ciwa;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

/** Routes game events to registered quests and provides helper lookups to UI/services. */
public class QuestEngine
{
    private final Client client;
    private final ClientThread clientThread;
    private final DialogueEngine dialogue;
    private final ProgressStore store;

    private final List<Quest> quests = new ArrayList<>();

    public QuestEngine(Client client, ClientThread clientThread, DialogueEngine dialogue, ProgressStore store)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dialogue = dialogue;
        this.store = store;
    }

    public void registerAll(List<Quest> qs) {
        quests.addAll(qs);
        quests.forEach(Quest::onStartUp);
    }

    public void reset() {
        quests.forEach(Quest::onShutDown);
        quests.clear();
    }

    public List<Quest> quests() { return quests; }

    /** Any quest want an Inquire on this npc? */
    public boolean anyInterestedIn(NPC npc) {
        for (Quest q : quests) if (q.interestedIn(npc)) return true;
        return false;
    }

    /** First quest that’s interested in this npc. You could change to multi-quest routing if needed. */
    @Nullable
    public Quest firstInterestedIn(NPC npc) {
        for (Quest q : quests) if (q.interestedIn(npc)) return q;
        return null;
    }

    public void handleInquire(NPC npc) {
        Quest q = firstInterestedIn(npc);
        if (q != null) q.onInquire(npc);
    }

    @Nullable
    public String overlayLabelFor(NPC npc) {
        for (Quest q : quests) {
            String l = q.overlayLabelFor(npc);
            if (l != null) return l;
        }
        return null;
    }

    // event fan-out
    public void onGameTick(GameTick e)        { quests.forEach(q -> q.onGameTick(e)); }
    public void onChatMessage(ChatMessage e)  { quests.forEach(q -> q.onChatMessage(e)); }
    public void onNpcSpawned(NpcSpawned e)    { quests.forEach(q -> q.onNpcSpawned(e)); }
    public void onNpcDespawned(NpcDespawned e){ quests.forEach(q -> q.onNpcDespawned(e)); }
    public void onConfigChanged(ConfigChanged e)
    {
        if ("Ciwascape".equals(e.getGroup()) && "resetQuest".equals(e.getKey()) && "true".equalsIgnoreCase(e.getNewValue()))
        {
            // Let each quest clear itself however it sees fit
            quests.forEach(q -> q.onConfigChanged(e));
        }
        else quests.forEach(q -> q.onConfigChanged(e));
    }

    // Helpers
    public static String baseNameOf(NPC npc) {
        if (npc == null || npc.getName() == null) return null;
        return Text.removeTags(npc.getName());
    }
}
