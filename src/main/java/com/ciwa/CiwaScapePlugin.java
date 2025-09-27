package com.ciwa;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Bootstraps the modular Ciwascape quest framework.
 * Wires: ProgressStore, QuestEngine, InteractionService, NpcTagOverlay.
 * On startup, scans already-present NPCs so quests initialize immediately.
 */
@Slf4j
@PluginDescriptor(
		name = "Ciwascape",
		description = "Modular quest framework with Inquire interactions and overhead dialog."
)
public class CiwaScapePlugin extends Plugin
{
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ConfigManager configManager;
	@Inject private OverlayManager overlayManager;

	@Inject private CiwaScapeConfig config;
	@Inject private DialogueEngine dialogue;

	// Services
	private ProgressStore progressStore;
	private QuestEngine questEngine;
	private InteractionService interactionService;

	// UI
	@Inject private NpcTagOverlay npcTagOverlay;

	@Provides
	CiwaScapeConfig provideConfig(ConfigManager cm) {
		return cm.getConfig(CiwaScapeConfig.class);
	}

	@Override
	protected void startUp()
	{
		// Core services
		progressStore = new ProgressStore(configManager);
		questEngine = new QuestEngine(client, clientThread, dialogue, progressStore);
		interactionService = new InteractionService(client, questEngine, config);

		// Register quests
		List<Quest> quests = new ArrayList<>();
		quests.add(new LostPkersQuest(client, clientThread, dialogue, progressStore, config));
		questEngine.registerAll(quests);

		// Wire overlay
		npcTagOverlay.setQuestEngine(questEngine);
		overlayManager.add(npcTagOverlay);

		// Initialize current scene
		scanExistingNpcs();

		log.info("Ciwascape started with {} quest(s).", quests.size());
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(npcTagOverlay);
		if (questEngine != null) questEngine.reset();
		interactionService = null;
		questEngine = null;
		progressStore = null;
		log.info("Ciwascape stopped.");
	}

	// ----- Event fanout -----

	@Subscribe
	public void onMenuEntryAdded(net.runelite.api.events.MenuEntryAdded e) {
		if (interactionService != null) interactionService.onMenuEntryAdded(e);
	}

	@Subscribe
	public void onMenuOptionClicked(net.runelite.api.events.MenuOptionClicked e) {
		if (interactionService != null) interactionService.onMenuOptionClicked(e);
	}

	@Subscribe
	public void onGameTick(GameTick e) {
		if (questEngine != null) questEngine.onGameTick(e);
		// IMPORTANT: advance multi-line overhead dialogue
		if (dialogue != null) dialogue.onGameTick(e);
	}

	@Subscribe
	public void onChatMessage(net.runelite.api.events.ChatMessage e) {
		if (questEngine != null) questEngine.onChatMessage(e);
	}

	@Subscribe
	public void onNpcSpawned(net.runelite.api.events.NpcSpawned e) {
		if (questEngine != null) questEngine.onNpcSpawned(e);
	}

	@Subscribe
	public void onNpcDespawned(net.runelite.api.events.NpcDespawned e) {
		if (questEngine != null) questEngine.onNpcDespawned(e);
	}

	@Subscribe
	public void onConfigChanged(net.runelite.client.events.ConfigChanged e) {
		if (questEngine != null) questEngine.onConfigChanged(e);
	}

	// ----- Helpers -----

	private void scanExistingNpcs()
	{
		if (client.getNpcs() == null || questEngine == null) return;

		for (NPC n : client.getNpcs())
		{
			if (n == null) continue;

			// Try common API: new NpcSpawned(n)
			try {
				net.runelite.api.events.NpcSpawned ev =
						new net.runelite.api.events.NpcSpawned(n);
				questEngine.onNpcSpawned(ev);
				continue;
			} catch (Throwable ignore) { /* fall through */ }

			// Fallback: reflective setNpc(n) on a no-arg ctor
			try {
				Class<?> cls = net.runelite.api.events.NpcSpawned.class;
				Object ev = cls.getDeclaredConstructor().newInstance();
				try {
					java.lang.reflect.Method m = cls.getDeclaredMethod("setNpc", NPC.class);
					m.setAccessible(true);
					m.invoke(ev, n);
					questEngine.onNpcSpawned((net.runelite.api.events.NpcSpawned) ev);
				} catch (NoSuchMethodException noSetter) {
					// If neither path works, skip; natural spawns will still initialize later.
				}
			} catch (Throwable ignore) {}
		}
	}
}
