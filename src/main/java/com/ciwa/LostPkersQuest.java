package com.ciwa;

import java.util.*;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;

import static com.ciwa.DialogueEngine.CYCLES_4S;
import static com.ciwa.QuestEngine.baseNameOf;

public class LostPkersQuest implements Quest
{
    private enum Phase { MEN, TALK_ANIMALS, HOLY_SYMBOLS, COMPLETE }

    private static final String QID = "lost-pkers";
    private static final String F_PHASE = "phase";
    private static final String F_CHICKEN_IDX = "chickenIdx";
    private static final String F_COW_IDX = "cowIdx";
    private static final String CHICKEN_LABEL = "MESSIAH";
    private static final String COW_LABEL = "Bombdeployer";

    private static final Set<Integer> LUMBRIDGE_REGIONS = new HashSet<>(Arrays.asList(12850, 12851, 12594, 12595));
    private final List<String> MEN_BANK_LUMBRIDGE = new ArrayList<>(Arrays.asList(
            "Roti Chicken","Lutra Otter","Gamecubetoby","FredsHotdog","Ciwa",
            "Spanky122","Huge Nut","Examine Thy","Daikin Park","Genga r"
    ));
    private final List<String> DEFAULT_MEN = Arrays.asList(
            "Kenneth","Luther","Magnus","Nigel","Osric","Percival","Quentin",
            "Rupert","Simon","Tristan","Ulric","Victor","Winston","Xavier","Yorick","Zachary"
    );

    private final List<String> PRE_QUEST = Arrays.asList(
            "Hey there %s, have you seen either of my friends? They went on a PK trip and never came back...",
            "(sigh) %s, you haven't seen my mates, have you? We split up on a PK trip and they never returned...",
            "Oi %s! My friends went out PKing and vanished. You heard anything?",
            "Hey %s, bit worried. My buddies went on a PK run and didn't make it back...",
            "You look capable, %s. Any news about two lads who went PKing and never returned?"
    );
    private final List<String> SEARCHING = Arrays.asList(
            "Any luck finding them?","Heard anything yet?","No sign of the boys?",
            "Still nothing? Keep looking, please.","They’ve gotta be out there somewhere..."
    );
    private final List<String> POST = Arrays.asList(
            "Glad the gang’s all back together. Been a weird day.",
            "I owe you one. Drinks at the Lumbridge pub sometime?",
            "If you see any cursed chickens again, I’m logging out.",
            "Never trusting PK invites from strangers again.",
            "What a saga. Next time I’m sticking to goblins."
    );

    private final Client client;
    private final net.runelite.client.callback.ClientThread clientThread;
    private final DialogueEngine dialogue;
    private final ProgressStore store;
    private final CiwaScapeConfig cfg;
    private final Random rng = new Random();

    // Men naming state
    private final Map<Integer, SpawnKey> menLiveKeys = new HashMap<>();
    private final Map<String, AreaState> areas = new HashMap<>();
    private final Map<String, Deque<String>> areaPriority = new HashMap<>();

    // Special animal indices
    private Integer chickenIdx = null;
    private Integer cowIdx = null;

    // Phase-2 tracking (talk-to-both-animals)
    private boolean talkedToChicken = false;
    private boolean talkedToCow = false;

    public LostPkersQuest(Client client,
                          net.runelite.client.callback.ClientThread clientThread,
                          DialogueEngine dialogue,
                          ProgressStore store,
                          CiwaScapeConfig cfg)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dialogue = dialogue;
        this.store = store;
        this.cfg = cfg;
        chickenIdx = parseIntOrNull(store.get(QID, F_CHICKEN_IDX, null));
        cowIdx = parseIntOrNull(store.get(QID, F_COW_IDX, null));
    }

    @Override public String id() { return QID; }
    @Override public String name() { return "Lost PKers"; }

    private Phase phase()
    {
        String s = store.get(QID, F_PHASE, Phase.MEN.name());
        try { return Phase.valueOf(s); } catch (Exception e) { return Phase.MEN; }
    }
    private void phase(Phase p) { store.put(QID, F_PHASE, p.name()); }

    @Override
    public boolean interestedIn(NPC npc)
    {
        String base = baseNameOf(npc);
        if (base == null) return false;
        if (base.equalsIgnoreCase("Man")) return true;
        if (base.equalsIgnoreCase("Chicken")) return true;
        if (base.equalsIgnoreCase("Cow")) return true;
        return false;
    }

    @Override
    public String overlayLabelFor(NPC npc)
    {
        String base = baseNameOf(npc);
        if (base == null) return null;

        if (base.equalsIgnoreCase("Chicken")) {
            return (phase() != Phase.COMPLETE && chickenIdx != null && npc.getIndex() == chickenIdx) ? CHICKEN_LABEL : null;
        }
        if (base.equalsIgnoreCase("Cow")) {
            return (phase() != Phase.COMPLETE && cowIdx != null && npc.getIndex() == cowIdx) ? COW_LABEL : null;
        }
        if (!base.equalsIgnoreCase("Man")) return null;

        SpawnKey liveKey = menLiveKeys.get(npc.getIndex());
        if (liveKey == null) return null;
        AreaState st = areas.get(areaIdFor(liveKey.regionId));
        if (st == null) return null;
        return st.assignments.get(liveKey);
    }

    @Override
    public void onInquire(NPC npc)
    {
        String base = baseNameOf(npc);
        if (base == null) return;

        String player = (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
                ? net.runelite.client.util.Text.sanitize(client.getLocalPlayer().getName())
                : "traveler";

        if (base.equalsIgnoreCase("Man"))
        {
            Phase ph = phase();
            switch (ph)
            {
                case MEN:
                {
                    String tmpl = PRE_QUEST.get(rng.nextInt(PRE_QUEST.size()));
                    speakSeq(npc, fmt(tmpl, player));
                    phase(Phase.TALK_ANIMALS);
                    break;
                }
                case TALK_ANIMALS:
                case HOLY_SYMBOLS:
                {
                    speakSeq(npc, pick(SEARCHING));
                    break;
                }
                case COMPLETE:
                {
                    speakSeq(npc, pick(POST));
                    break;
                }
                default: break;
            }
        }
        else if (base.equalsIgnoreCase("Cow"))
        {
            retargetCowIfNeeded();
            if (cowIdx != null && npc.getIndex() == cowIdx && (phase() == Phase.TALK_ANIMALS || phase() == Phase.HOLY_SYMBOLS))
            {
                speakSeq(npc,
                        "Help! We were cursed into animals by some salty PKer wearing an UNHOLY symbol!",
                        "My mate MESSIAH was cursed too."
                );
                talkedToCow = true;
                maybeAdvanceFromPhase2();
            }
        }
        else if (base.equalsIgnoreCase("Chicken"))
        {
            retargetChickenIfNeeded();
            if (chickenIdx != null && npc.getIndex() == chickenIdx && (phase() == Phase.TALK_ANIMALS || phase() == Phase.HOLY_SYMBOLS))
            {
                speakSeq(npc,
                        "BAWK— ahem. Yeah, I’m cursed too.",
                        "If you’ve got something HOLY, that might undo this."
                );
                talkedToChicken = true;
                maybeAdvanceFromPhase2();
            }
        }
    }

    private void maybeAdvanceFromPhase2()
    {
        if (phase() == Phase.TALK_ANIMALS && talkedToChicken && talkedToCow)
        {
            phase(Phase.HOLY_SYMBOLS);
            sayToChat(playerNameOr("traveler") + ": I need to help these guys! It'll probably take something really HOLY to fix them though..");
        }
    }

    @Override
    public void onNpcSpawned(NpcSpawned e)
    {
        NPC npc = e.getNpc();
        String base = baseNameOf(npc);
        if (base == null) return;

        if (base.equalsIgnoreCase("Man")) onSeenMan(npc);
        else if (base.equalsIgnoreCase("Chicken") && chickenIdx == null) { chickenIdx = npc.getIndex(); persistIdx(); }
        else if (base.equalsIgnoreCase("Cow") && cowIdx == null) { cowIdx = npc.getIndex(); persistIdx(); }
    }

    @Override
    public void onNpcDespawned(NpcDespawned e)
    {
        NPC npc = e.getNpc();
        String base = baseNameOf(npc);
        if (base == null) return;

        if (base.equalsIgnoreCase("Man"))
        {
            SpawnKey key = menLiveKeys.remove(npc.getIndex());
            if (key != null) {
                AreaState st = areas.get(areaIdFor(key.regionId));
                if (st != null) {
                    String assigned = st.assignments.get(key);
                    if (assigned != null) st.inUse.remove(assigned);
                }
            }
        }
        else if (base.equalsIgnoreCase("Chicken") && chickenIdx != null && npc.getIndex() == chickenIdx) {
            chickenIdx = null; persistIdx(); retargetChickenIfNeeded();
        }
        else if (base.equalsIgnoreCase("Cow") && cowIdx != null && npc.getIndex() == cowIdx) {
            cowIdx = null; persistIdx(); retargetCowIfNeeded();
        }
    }

    @Override
    public void onChatMessage(ChatMessage e)
    {
        if (phase() != Phase.HOLY_SYMBOLS) return;
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) return;
        String lower = net.runelite.client.util.Text.removeTags(msg).trim().toLowerCase(Locale.ROOT);

        if (lower.contains("the cow doesn't want that") && !getFlag("cowSeen"))
        {
            setFlag("cowSeen", true);
            sayToChat("Bombdeployer: It appears Bombdeployer is almost too far gone to be saved...");
            sayToChat("Bombdeployer: *The cows eyes glisten*");
            speakSeq(findNearestByName("Cow"), "Thanks friend! I can feel myself coming back.");
            maybeComplete();
        }

        if ((lower.contains("nothing interesting happens") || lower.contains("the chicken doesn't want that")) && !getFlag("chickenSeen"))
        {
            setFlag("chickenSeen", true);
            sayToChat("MESSIAH: Wait, something really interesting is happening!");
            sayToChat("MESSIAH: *The chickens eyes glisten*");
            speakSeq(findNearestByName("Chicken"), "Fuck me mate that was awful, those eggs HURT coming out!");
            maybeComplete();
        }
    }

    @Override
    public void onConfigChanged(net.runelite.client.events.ConfigChanged e)
    {
        if (!"Ciwascape".equals(e.getGroup())) return;
        if ("resetQuest".equals(e.getKey()) && "true".equalsIgnoreCase(e.getNewValue())) {
            resetAll();
        }
    }

    // ---- Dialogue helpers ----

    private void speakSeq(NPC npc, String... lines)
    {
        if (npc == null || lines == null || lines.length == 0) return;

        List<DialogueEngine.Line> seq = new ArrayList<>(lines.length);
        for (String l : lines) if (l != null && !l.isEmpty()) seq.add(new DialogueEngine.Line(l, CYCLES_4S));

        final String chatPrefix = displayNameFor(npc);

        // Append to current run for this NPC if present; otherwise start.
        dialogue.startOrAppend(
                npc,
                seq,
                new java.util.function.Consumer<String>() {
                    @Override public void accept(String text) {
                        if (cfg.echoToChat()) sayToChat(chatPrefix + ": " + text);
                    }
                },
                new java.util.function.Consumer<Void>() {
                    @Override public void accept(Void v) { /* no-op */ }
                }
        );
    }

    private String displayNameFor(NPC npc)
    {
        String overlay = overlayLabelFor(npc);
        if (overlay != null && !overlay.isEmpty()) return overlay;
        String base = baseNameOf(npc);
        return base != null ? base : "NPC";
    }

    private void sayToChat(String text) {
        clientThread.invoke(new Runnable() {
            @Override public void run() {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", text, null);
            }
        });
    }

    private String playerNameOr(String fallback)
    {
        return (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
                ? net.runelite.client.util.Text.sanitize(client.getLocalPlayer().getName())
                : fallback;
    }

    // ---- Men naming logic ----

    private void onSeenMan(NPC npc)
    {
        WorldPoint wp = WorldPoint.fromLocalInstance(client, npc.getLocalLocation());
        if (wp == null) return;

        SpawnKey key = new SpawnKey(npc.getId(), wp.getRegionID(), wp.getX(), wp.getY(), wp.getPlane());
        AreaState st = areas.computeIfAbsent(areaIdFor(key.regionId), new java.util.function.Function<String, AreaState>() {
            @Override public AreaState apply(String s) { return makeArea(s); }
        });

        menLiveKeys.put(npc.getIndex(), key);

        String existing = st.assignments.get(key);
        if (existing != null) {
            st.inUse.add(existing);
            return;
        }

        Deque<String> q = areaPriority.get(areaIdFor(key.regionId));
        if (q != null && !q.isEmpty())
        {
            for (Iterator<String> it = q.iterator(); it.hasNext(); )
            {
                String cand = it.next();
                if (!st.inUse.contains(cand))
                {
                    st.assignments.put(key, cand);
                    st.inUse.add(cand);
                    it.remove();
                    return;
                }
            }
        }

        List<String> bank = st.bank;
        if (bank.isEmpty()) return;

        int start = Math.floorMod(key.hashCode(), bank.size());
        String chosen = null;
        for (int i = 0; i < bank.size(); i++) {
            String cand = bank.get((start + i) % bank.size());
            if (!st.inUse.contains(cand)) { chosen = cand; break; }
        }
        if (chosen == null) chosen = bank.get(start);

        st.assignments.put(key, chosen);
        st.inUse.add(chosen);
    }

    private AreaState makeArea(String areaId)
    {
        if ("lumbridge".equals(areaId)) {
            List<String> base = new ArrayList<>(MEN_BANK_LUMBRIDGE);
            if (phase() == Phase.COMPLETE) {
                if (!base.contains(CHICKEN_LABEL)) base.add(CHICKEN_LABEL);
                if (!base.contains(COW_LABEL))     base.add(COW_LABEL);
            }
            return new AreaState(base);
        }
        return new AreaState(DEFAULT_MEN);
    }

    private String areaIdFor(int regionId) {
        return LUMBRIDGE_REGIONS.contains(regionId) ? "lumbridge" : ("region-" + regionId);
    }

    private void retargetChickenIfNeeded()
    {
        if (chickenIdx != null) return;
        NPC n = findNearestByName("Chicken");
        if (n != null) { chickenIdx = n.getIndex(); persistIdx(); }
    }

    private void retargetCowIfNeeded()
    {
        if (cowIdx != null) return;
        NPC n = findNearestByName("Cow");
        if (n != null) { cowIdx = n.getIndex(); persistIdx(); }
    }

    private NPC findNearestByName(String baseName)
    {
        if (client.getNpcs() == null) return null;
        WorldPoint pw = (client.getLocalPlayer() != null) ? client.getLocalPlayer().getWorldLocation() : null;
        NPC best = null; int bestDist = Integer.MAX_VALUE;
        for (NPC n : client.getNpcs())
        {
            String base = baseNameOf(n);
            if (base == null || !base.equalsIgnoreCase(baseName)) continue;
            if (pw == null) return n;
            WorldPoint nw = WorldPoint.fromLocalInstance(client, n.getLocalLocation());
            if (nw == null) continue;
            int dist = Math.max(Math.abs(pw.getX() - nw.getX()), Math.abs(pw.getY() - nw.getY()));
            if (dist < bestDist) { bestDist = dist; best = n; }
        }
        return best;
    }

    private void maybeComplete()
    {
        boolean cowSeen = getFlag("cowSeen");
        boolean chickenSeen = getFlag("chickenSeen");
        if (phase() == Phase.HOLY_SYMBOLS && cowSeen && chickenSeen)
        {
            phase(Phase.COMPLETE);
            Deque<String> q = areaPriority.computeIfAbsent("lumbridge", new java.util.function.Function<String, Deque<String>>() {
                @Override public Deque<String> apply(String s) { return new ArrayDeque<String>(); }
            });
            if (!q.contains(CHICKEN_LABEL)) q.addLast(CHICKEN_LABEL);
            if (!q.contains(COW_LABEL))     q.addLast(COW_LABEL);
            sayToChat("Thanks for saving us. We'll head back to the boys now.");
        }
    }

    private void resetAll()
    {
        phase(Phase.MEN);
        setFlag("cowSeen", false);
        setFlag("chickenSeen", false);
        talkedToChicken = false;
        talkedToCow = false;
        chickenIdx = null; cowIdx = null; persistIdx();
        menLiveKeys.clear(); areas.clear(); areaPriority.clear();
    }

    private void persistIdx()
    {
        store.put(QID, F_CHICKEN_IDX, chickenIdx == null ? "" : Integer.toString(chickenIdx));
        store.put(QID, F_COW_IDX, cowIdx == null ? "" : Integer.toString(cowIdx));
    }

    private static String fmt(String t, String arg) { return t.contains("%s") ? String.format(t, arg) : t; }
    private String pick(List<String> list) { return list.get(rng.nextInt(list.size())); }

    private boolean getFlag(String name) { return "1".equals(store.get(QID, "flag."+name, "0")); }
    private void setFlag(String name, boolean v) { store.put(QID, "flag."+name, v ? "1" : "0"); }

    private static final class AreaState {
        final List<String> bank;
        final Map<SpawnKey, String> assignments = new HashMap<>();
        final Set<String> inUse = new HashSet<>();
        AreaState(List<String> base) { this.bank = new ArrayList<>(base); }
    }
    private static final class SpawnKey {
        final int npcId, regionId, worldX, worldY, plane;
        SpawnKey(int npcId, int regionId, int worldX, int worldY, int plane) {
            this.npcId=npcId; this.regionId=regionId; this.worldX=worldX; this.worldY=worldY; this.plane=plane;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SpawnKey)) return false;
            SpawnKey k = (SpawnKey) o;
            return npcId==k.npcId && regionId==k.regionId && worldX==k.worldX && worldY==k.worldY && plane==k.plane;
        }
        @Override public int hashCode() { return Objects.hash(npcId, regionId, worldX, worldY, plane); }
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Integer.parseInt(s); } catch (Exception e) { return null; }
    }
}
