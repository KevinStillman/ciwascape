package com.ciwa;

import com.google.inject.Provides;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(name = "Ciwascape")
public class CiwaScapePlugin extends Plugin
{
	/* -------------------- Quest phases -------------------- */
	private enum Phase { MEN, TALK_ANIMALS, HOLY_SYMBOLS, COMPLETE }
	private Phase phase = Phase.MEN;

	/* --------- Area banks --------- */
	private static final List<String> LUMBRIDGE_MEN_BASE = List.of(
			"Roti Chicken", "Lutra Otter", "Gamecubetoby", "FredsHotdog", "Ciwa",
			"Spanky122", "Huge Nut", "Examine Thy", "Daikin Park", "Genga r"
	);

	private static final List<String> DEFAULT_MEN = List.of(
			"Kenneth","Luther","Magnus","Nigel","Osric",
			"Percival","Quentin","Rupert","Simon","Tristan",
			"Ulric","Victor","Winston","Xavier","Yorick","Zachary"
	);

	// Example Lumbridge regions (adjust if needed)
	private static final Set<Integer> LUMBRIDGE_REGIONS = new HashSet<>(Arrays.asList(
			12850, 12851, 12594, 12595
	));

	/* --------- Special animals --------- */
	private static final String CHICKEN_NAME = "MESSIAH";
	private static final String COW_NAME     = "Bombdeployer";

	/* --------- Dialogue banks --------- */
	private static final List<String> PRE_QUEST_OPENERS = List.of(
			"Hey there %s, have you seen either of my friends? They went on a PK trip and never came back...",
			"(sigh) %s, you haven't seen my mates, have you? We split up on a PK trip and they never returned...",
			"Oi %s! My friends went out PKing and vanished. You heard anything?",
			"Hey %s, bit worried. My buddies went on a PK run and didn't make it back...",
			"You look capable, %s. Any news about two lads who went PKing and never returned?"
	);

	private static final List<String> SEARCHING_MEN_LINES = List.of(
			"Any luck finding them?",
			"Heard anything yet?",
			"No sign of the boys?",
			"Still nothing? Keep looking, please.",
			"They’ve gotta be out there somewhere..."
	);

	private static final List<String> POST_QUEST_BANTER = List.of(
			"Glad the gang’s all back together. Been a weird day.",
			"I owe you one. Drinks at the Lumbridge pub sometime?",
			"If you see any cursed chickens again, I’m logging out.",
			"Never trusting PK invites from strangers again.",
			"What a saga. Next time I’m sticking to goblins."
	);

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ScheduledExecutorService scheduledExecutorService;
	@Inject private OverlayManager overlayManager;
	@Inject private CiwaScapeNpcOverlay npcOverlay;

	@Inject private CiwaScapeConfig config; // contains reset toggle
	@Inject private ConfigManager configManager;

	private final Random rng = new Random();

	/* ---------- Area-bank state for Men ---------- */

	/** Live NPC index -> spawn key (to manage in-use slots) */
	private final Map<Integer, SpawnKey> liveNpcKeys = new HashMap<>();

	/** Per-area state (bank is mutable for runtime updates) */
	private static final class AreaState {
		final List<String> bank;
		final Map<SpawnKey, String> assignments = new HashMap<>();
		final Set<String> inUse = new HashSet<>();
		AreaState(List<String> base) { this.bank = new ArrayList<>(base); }
	}
	private final Map<String, AreaState> areas = new HashMap<>();

	/** Priority names to consume first when assigning to Men per area (e.g., after quest completion). */
	private final Map<String, Deque<String>> areaPriorityNames = new HashMap<>();

	/* ---------- Special “one labeled” Chicken & Cow (by NPC index) ---------- */
	private Integer specialChickenIndex = null; // the chicken who gets "MESSIAH"
	private Integer specialCowIndex     = null; // the cow who gets "Bombdeployer"

	/* ---------- Phase flags ---------- */
	private boolean talkedToChicken = false;   // examined chicken (phase 2)
	private boolean talkedToCow     = false;   // examined cow (phase 2)

	private boolean cowHolySeen     = false;   // saw cow symbol-use message (phase 3)
	private boolean chickenHolySeen = false;   // saw chicken symbol-use message (phase 3)

	/* ---------- DI / Config ---------- */

	@Provides
	CiwaScapeConfig provideConfig(ConfigManager cm) { return cm.getConfig(CiwaScapeConfig.class); }

	/* ---------- Lifecycle ---------- */

	@Override
	protected void startUp()
	{
		overlayManager.add(npcOverlay);

		if (client.getNpcs() != null)
		{
			for (NPC npc : client.getNpcs())
			{
				if (npc == null || npc.getName() == null) continue;
				final String base = Text.removeTags(npc.getName());
				if (base == null) continue;

				if (base.equalsIgnoreCase("Man")) {
					onSeenMan(npc);
				} else if (base.equalsIgnoreCase("Chicken") && specialChickenIndex == null) {
					specialChickenIndex = npc.getIndex();
				} else if (base.equalsIgnoreCase("Cow") && specialCowIndex == null) {
					specialCowIndex = npc.getIndex();
				}
			}
		}
		log.info("Ciwascape started!");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(npcOverlay);

		liveNpcKeys.clear();
		areas.clear();
		areaPriorityNames.clear();
		specialChickenIndex = null;
		specialCowIndex = null;

		phase = Phase.MEN;
		talkedToChicken = false;
		talkedToCow = false;
		cowHolySeen = false;
		chickenHolySeen = false;

		log.info("Ciwascape stopped!");
	}

	/* ---------- Config reset ---------- */

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!"Ciwascape".equals(e.getGroup())) return;
		if (!"resetQuest".equals(e.getKey())) return;

		if ("true".equalsIgnoreCase(e.getNewValue()))
		{
			clientThread.invokeLater(this::resetQuestProgress);
			configManager.setConfiguration("Ciwascape", "resetQuest", false);
		}
	}

	private void resetQuestProgress()
	{
		phase = Phase.MEN;

		talkedToChicken = false;
		talkedToCow = false;
		cowHolySeen = false;
		chickenHolySeen = false;

		liveNpcKeys.clear();
		areas.clear();
		areaPriorityNames.clear();

		specialChickenIndex = null;
		specialCowIndex = null;

		retargetChicken();
		retargetCow();

		sendGameMessage(red("Ciwascape quest progress has been reset."));
	}

	/* ---------- Scene resets ---------- */

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		switch (e.getGameState())
		{
			case HOPPING:
			case LOADING:
			case LOGIN_SCREEN:
				specialChickenIndex = null;
				specialCowIndex = null;

				liveNpcKeys.clear();
				areas.clear();
				areaPriorityNames.clear();
				break;
			default:
				break;
		}
	}

	/* ---------- Spawns / Despawns ---------- */

	@Subscribe
	public void onNpcSpawned(NpcSpawned e)
	{
		final NPC npc = e.getNpc();
		if (npc == null || npc.getName() == null) return;

		final String base = Text.removeTags(npc.getName());
		if (base == null) return;

		if (base.equalsIgnoreCase("Man")) {
			onSeenMan(npc);
		} else if (base.equalsIgnoreCase("Chicken")) {
			if (specialChickenIndex == null) specialChickenIndex = npc.getIndex();
		} else if (base.equalsIgnoreCase("Cow")) {
			if (specialCowIndex == null) specialCowIndex = npc.getIndex();
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned e)
	{
		final NPC npc = e.getNpc();
		if (npc == null) return;

		final SpawnKey key = liveNpcKeys.remove(npc.getIndex());
		if (key != null)
		{
			final String areaId = areaIdFor(key.regionId);
			final AreaState state = areas.get(areaId);
			if (state != null)
			{
				final String assigned = state.assignments.get(key);
				if (assigned != null) state.inUse.remove(assigned);
			}
		}

		if (specialChickenIndex != null && npc.getIndex() == specialChickenIndex) {
			specialChickenIndex = null;
			retargetChicken();
		}
		if (specialCowIndex != null && npc.getIndex() == specialCowIndex) {
			specialCowIndex = null;
			retargetCow();
		}
	}

	/* ---------- Examine-driven “talk” ---------- */

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		if (e.getMenuAction() != MenuAction.EXAMINE_NPC)
			return;

		final int idx = e.getId();
		final NPC clicked = npcByIndex(idx);
		if (clicked == null || clicked.getName() == null)
			return;

		final String base = Text.removeTags(clicked.getName());
		if (base == null)
			return;

		final String player = playerNameOr("traveler");

		if (base.equalsIgnoreCase("Man"))
		{
			if (phase == Phase.MEN)
			{
				String tmpl = PRE_QUEST_OPENERS.get(rng.nextInt(PRE_QUEST_OPENERS.size()));
				scheduleMsg(red(String.format("%s: %s", safeAssignedName(clicked), fmtWithPlayer(tmpl, player))), 150);
				phase = Phase.TALK_ANIMALS;
			}
			else if (phase == Phase.TALK_ANIMALS || phase == Phase.HOLY_SYMBOLS)
			{
				scheduleMsg(red(String.format("%s: %s", safeAssignedName(clicked),
						SEARCHING_MEN_LINES.get(rng.nextInt(SEARCHING_MEN_LINES.size())))), 150);
			}
			else if (phase == Phase.COMPLETE)
			{
				scheduleMsg(red(String.format("%s: %s", safeAssignedName(clicked),
						POST_QUEST_BANTER.get(rng.nextInt(POST_QUEST_BANTER.size())))), 150);
			}
		}
		else if (base.equalsIgnoreCase("Cow"))
		{
			if (specialCowIndex == null) retargetCow();
			if (specialCowIndex != null && clicked.getIndex() == specialCowIndex)
			{
				if (phase == Phase.TALK_ANIMALS || phase == Phase.HOLY_SYMBOLS)
				{
					scheduleMsg(red("Bombdeployer: Help! We were cursed into animals by some salty PKer wearing an UNHOLY symbol!"), 150);
					scheduleMsg(red("Bombdeployer: My mate MESSIAH was cursed too."), 1150);
					talkedToCow = true;
					tryAdvanceFromPhase2();
				}
			}
		}
		else if (base.equalsIgnoreCase("Chicken"))
		{
			if (specialChickenIndex == null) retargetChicken();
			if (specialChickenIndex != null && clicked.getIndex() == specialChickenIndex)
			{
				if (phase == Phase.TALK_ANIMALS || phase == Phase.HOLY_SYMBOLS)
				{
					scheduleMsg(red("MESSIAH: BAWK— ahem. Yeah, I’m cursed too."), 150);
					scheduleMsg(red("MESSIAH: If you’ve got something HOLY, that might undo this."), 1150);
					talkedToChicken = true;
					tryAdvanceFromPhase2();
				}
			}
		}
	}

	private void tryAdvanceFromPhase2()
	{
		if (phase == Phase.TALK_ANIMALS && talkedToChicken && talkedToCow)
		{
			phase = Phase.HOLY_SYMBOLS;
			scheduleMsg(red(String.format("%s: I need to help these guys! It'll probably take something really HOLY to fix them though..",
					playerNameOr("traveler"))), 300);
		}
	}

	/* ---------- Phase 3: ONLY watch for the two chat lines (ultra-forgiving) ---------- */
	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (phase != Phase.HOLY_SYMBOLS)
			return;

		final String raw = e.getMessage();
		if (raw == null || raw.isEmpty())
			return;

		final String msg = Text.removeTags(raw).trim();
		final String lower = msg.toLowerCase(Locale.ROOT);

		// Cow progress
		if (!cowHolySeen && lower.contains("the cow doesn't want that"))
		{
			cowHolySeen = true;
			scheduleMsg(red("It appears Bombdeployer is almost too far gone to be saved..."), 50);
			scheduleMsg(red("*The cows eyes glisten*"), 1050);
			scheduleMsg(red(String.format("Bombdeployer: Thanks %s! I can feel myself coming back.", playerNameOr("friend"))), 2050);
			maybeAdvanceToComplete(3050);
		}

		// Chicken progress
		if (!chickenHolySeen && (lower.contains("nothing interesting happens") || lower.contains("the chicken doesn't want that")))
		{
			chickenHolySeen = true;
			scheduleMsg(red("Wait, something really interesting is happening!"), 50);
			scheduleMsg(red("*The chickens eyes glisten*"), 1050);
			scheduleMsg(red("MESSIAH: Fuck me mate that was awful, those eggs HURT coming out!"), 2050);
			maybeAdvanceToComplete(3050);
		}
	}

	private void maybeAdvanceToComplete(long startDelayMs)
	{
		if (cowHolySeen && chickenHolySeen && phase == Phase.HOLY_SYMBOLS)
		{
			phase = Phase.COMPLETE;

			// Remove animal labels and add names to Lumbridge men bank
			clientThread.invokeLater(() -> {
				specialChickenIndex = null;
				specialCowIndex = null;

				AreaState lum = areas.get("lumbridge");
				if (lum != null)
				{
					if (!lum.bank.contains(CHICKEN_NAME)) lum.bank.add(CHICKEN_NAME);
					if (!lum.bank.contains(COW_NAME))     lum.bank.add(COW_NAME);
				}

				// Enqueue priority usage: first two Man assignments should be these names
				Deque<String> q = areaPriorityNames.computeIfAbsent("lumbridge", k -> new ArrayDeque<>());
				// Avoid duplicates if somehow already queued
				if (!q.contains(CHICKEN_NAME)) q.addLast(CHICKEN_NAME);
				if (!q.contains(COW_NAME))     q.addLast(COW_NAME);
			});

			scheduleMsg(red("Thanks for saving us. We'll head back to the boys now."), startDelayMs);
		}
	}

	/* ---------- Men: area-based unique naming with PRIORITY ---------- */

	private void onSeenMan(NPC npc)
	{
		final WorldPoint wp = WorldPoint.fromLocalInstance(client, npc.getLocalLocation());
		if (wp == null) return;

		final SpawnKey key = new SpawnKey(npc.getId(), wp.getRegionID(), wp.getX(), wp.getY(), wp.getPlane());
		final String areaId = areaIdFor(key.regionId);
		final AreaState state = areas.computeIfAbsent(areaId, this::makeArea);

		liveNpcKeys.put(npc.getIndex(), key);

		// Keep existing assignment
		final String existing = state.assignments.get(key);
		if (existing != null) {
			state.inUse.add(existing);
			return;
		}

		// 1) Try priority names first (e.g., after quest completion)
		Deque<String> pq = areaPriorityNames.get(areaId);
		if (pq != null && !pq.isEmpty())
		{
			// We might have priority names that are already in use; skip them until we find a free one
			Iterator<String> it = pq.iterator();
			while (it.hasNext())
			{
				String candidate = it.next();
				if (!state.inUse.contains(candidate))
				{
					state.assignments.put(key, candidate);
					state.inUse.add(candidate);
					it.remove(); // consume this priority slot
					return;
				}
			}
		}

		// 2) Fall back to normal bank assignment (hash start, first free)
		final List<String> bank = state.bank;
		if (bank.isEmpty()) return;

		int start = Math.floorMod(key.hashCode(), bank.size());
		String chosen = null;
		for (int i = 0; i < bank.size(); i++) {
			String candidate = bank.get((start + i) % bank.size());
			if (!state.inUse.contains(candidate)) {
				chosen = candidate;
				break;
			}
		}
		if (chosen == null) {
			chosen = bank.get(start);
		}

		state.assignments.put(key, chosen);
		state.inUse.add(chosen);
	}

	private AreaState makeArea(String areaId)
	{
		if ("lumbridge".equals(areaId))
		{
			List<String> base = new ArrayList<>(LUMBRIDGE_MEN_BASE);
			if (phase == Phase.COMPLETE)
			{
				if (!base.contains(CHICKEN_NAME)) base.add(CHICKEN_NAME);
				if (!base.contains(COW_NAME))     base.add(COW_NAME);
			}
			return new AreaState(base);
		}
		return new AreaState(DEFAULT_MEN);
	}

	private String areaIdFor(int regionId)
	{
		if (LUMBRIDGE_REGIONS.contains(regionId)) return "lumbridge";
		return "region-" + regionId;
	}

	/* ---------- Overlay API ---------- */

	String getAssignedName(NPC npc)
	{
		if (npc == null || npc.getName() == null) return null;
		final String base = Text.removeTags(npc.getName());
		if (base == null) return null;

		if (base.equalsIgnoreCase("Chicken"))
		{
			if (phase == Phase.COMPLETE) return null;
			return (specialChickenIndex != null && npc.getIndex() == specialChickenIndex) ? CHICKEN_NAME : null;
		}
		if (base.equalsIgnoreCase("Cow"))
		{
			if (phase == Phase.COMPLETE) return null;
			return (specialCowIndex != null && npc.getIndex() == specialCowIndex) ? COW_NAME : null;
		}

		if (base.equalsIgnoreCase("Man"))
		{
			final SpawnKey liveKey = liveNpcKeys.get(npc.getIndex());
			if (liveKey == null) return null;

			final AreaState state = areas.get(areaIdFor(liveKey.regionId));
			if (state == null) return null;

			return state.assignments.get(liveKey);
		}

		return null;
	}

	Client getClient() { return client; }

	/* ---------- Helpers ---------- */

	private NPC npcByIndex(Integer idx)
	{
		if (idx == null || client.getNpcs() == null) return null;
		for (NPC n : client.getNpcs())
		{
			if (n != null && n.getIndex() == idx) return n;
		}
		return null;
	}

	private String safeAssignedName(NPC man)
	{
		String name = getAssignedName(man);
		return name != null ? name : "Man";
	}

	private String playerNameOr(String fallback)
	{
		return (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
				? Text.sanitize(client.getLocalPlayer().getName())
				: fallback;
	}

	private String fmtWithPlayer(String template, String player)
	{
		return template.contains("%s") ? String.format(template, player) : template;
	}

	private void scheduleMsg(String msg, long delayMs)
	{
		scheduledExecutorService.schedule(
				() -> clientThread.invokeLater(() -> sendGameMessage(msg)),
				delayMs, TimeUnit.MILLISECONDS
		);
	}

	private void sendGameMessage(String msg)
	{
		if (client.getGameState() == GameState.LOGGED_IN) {
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", red(msg), null);
		}
	}

	private static String red(String s) { return "<col=ff0000>" + s + "</col>"; }

	private Integer pickNearestIndexByName(String baseName)
	{
		if (client.getNpcs() == null) return null;

		WorldPoint pw = (client.getLocalPlayer() != null) ? client.getLocalPlayer().getWorldLocation() : null;
		NPC best = null;
		int bestDist = Integer.MAX_VALUE;

		for (NPC n : client.getNpcs())
		{
			if (n == null || n.getName() == null) continue;
			String base = Text.removeTags(n.getName());
			if (base == null || !base.equalsIgnoreCase(baseName)) continue;

			if (pw == null) {
				return n.getIndex();
			}

			WorldPoint nw = WorldPoint.fromLocalInstance(client, n.getLocalLocation());
			if (nw == null) continue;
			int dx = Math.abs(pw.getX() - nw.getX());
			int dy = Math.abs(pw.getY() - nw.getY());
			int dist = Math.max(dx, dy);
			if (dist < bestDist) {
				bestDist = dist;
				best = n;
			}
		}
		return best != null ? best.getIndex() : null;
	}

	private void retargetChicken()
	{
		Integer idx = pickNearestIndexByName("Chicken");
		if (idx != null) {
			specialChickenIndex = idx;
			log.debug("Retargeted special chicken to index {}", idx);
		}
	}

	private void retargetCow()
	{
		Integer idx = pickNearestIndexByName("Cow");
		if (idx != null) {
			specialCowIndex = idx;
			log.debug("Retargeted special cow to index {}", idx);
		}
	}

	/* --------- key type for Men --------- */
	private static final class SpawnKey {
		final int npcId, regionId, worldX, worldY, plane;
		SpawnKey(int npcId, int regionId, int worldX, int worldY, int plane) {
			this.npcId = npcId; this.regionId = regionId; this.worldX = worldX; this.worldY = worldY; this.plane = plane;
		}
		@Override public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof SpawnKey)) return false;
			SpawnKey k = (SpawnKey) o;
			return npcId == k.npcId && regionId == k.regionId && worldX == k.worldX && worldY == k.worldY && plane == k.plane;
		}
		@Override public int hashCode() {
			return Objects.hash(npcId, regionId, worldX, worldY, plane);
		}
	}
}
