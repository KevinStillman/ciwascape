package com.ciwa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/** Overhead text engine measured in CLIENT CYCLES (50/sec). Supports appending lines to the current NPC. */
@Singleton
public class DialogueEngine
{
    public static final int CYCLES_PER_SECOND = 50;
    public static final int CYCLES_4S = 4 * CYCLES_PER_SECOND; // 200 cycles ≈ 4 seconds

    public static final class Line
    {
        private final String text;
        private final int cycles;
        public Line(String text, int cycles) { this.text = text; this.cycles = cycles; }
        public String getText() { return text; }
        public int getCycles() { return cycles; }
    }

    private static final class Active
    {
        NPC npc;
        Deque<Line> queue = new ArrayDeque<>();
        Consumer<String> onLineShown;
        Consumer<Void> onEnd;
        boolean showing;
    }

    @Inject private Client client;
    @Inject private ClientThread clientThread;

    private Active active;

    /** Start a new dialogue sequence (replaces any existing one). */
    public void start(NPC npc, Iterable<Line> lines, Consumer<String> onLineShown, Consumer<Void> onEnd)
    {
        if (npc == null) return;
        Active a = new Active();
        a.npc = npc;
        for (Line l : lines)
        {
            if (l != null && l.getText() != null && !l.getText().isEmpty() && l.getCycles() > 0)
                a.queue.addLast(l);
        }
        a.onLineShown = onLineShown;
        a.onEnd = onEnd;
        active = a;

        Line first = a.queue.peekFirst();
        if (first != null)
        {
            clientThread.invoke(() -> showLine(npc, first, a.onLineShown));
            a.showing = true;
        }
        else
        {
            complete();
        }
    }

    /** Append lines to the current NPC’s dialogue if it’s the same NPC; otherwise start fresh. */
    public void startOrAppend(NPC npc, Iterable<Line> lines, Consumer<String> onLineShown, Consumer<Void> onEnd)
    {
        if (active != null && Objects.equals(active.npc, npc))
        {
            List<Line> buf = new ArrayList<>();
            for (Line l : lines)
            {
                if (l != null && l.getText() != null && !l.getText().isEmpty() && l.getCycles() > 0)
                    buf.add(l);
            }
            for (Line l : buf) active.queue.addLast(l);

            if (active.onLineShown == null) active.onLineShown = onLineShown;
            if (active.onEnd == null) active.onEnd = onEnd;

            // If nothing is currently showing, kick off the next line immediately
            if (!active.showing || npc.getOverheadText() == null || npc.getOverheadCycle() <= 0)
            {
                final Line next = active.queue.peekFirst();
                if (next != null)
                {
                    clientThread.invoke(() -> showLine(npc, next, active.onLineShown));
                    active.showing = true;
                }
            }
        }
        else
        {
            start(npc, lines, onLineShown, onEnd);
        }
    }

    public boolean isRunningFor(NPC npc)
    {
        return active != null && Objects.equals(active.npc, npc);
    }

    private void showLine(NPC npc, Line line, Consumer<String> cb)
    {
        if (npc == null || line == null) return;
        npc.setOverheadText(line.getText());
        npc.setOverheadCycle(line.getCycles()); // CLIENT cycles (50/sec)
        if (cb != null) cb.accept(line.getText()); // echo to chat exactly when the overhead appears
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (active == null) return;
        final NPC npc = active.npc;
        if (npc == null)
        {
            complete();
            return;
        }

        // Advance when current line finishes displaying
        if (!active.showing || npc.getOverheadText() == null || npc.getOverheadCycle() <= 0)
        {
            active.queue.pollFirst(); // consume current

            final Line next = active.queue.peekFirst();
            if (next == null)
            {
                complete();
                return;
            }

            clientThread.invoke(() -> showLine(npc, next, active.onLineShown));
            active.showing = true;
        }
    }

    private void complete()
    {
        if (active == null) return;
        final NPC npc = active.npc;
        final Consumer<Void> end = active.onEnd;

        if (npc != null)
        {
            clientThread.invoke(() -> npc.setOverheadText(null));
        }

        active = null;

        if (end != null)
        {
            try { end.accept(null); } catch (Exception ignored) { }
        }
    }
}
