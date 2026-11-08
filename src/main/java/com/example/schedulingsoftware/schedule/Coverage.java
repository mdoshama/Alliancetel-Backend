package com.example.schedulingsoftware.schedule;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.store.Store;

/**
 * Can the stores of an area be covered on a day, given who is off? Follows the same rules the schedule uses to
 * place people: a primary (or a manager) only works at their own store, a secondary works at their store or is
 * moved to another one (only the stores they float to, if they have a list), and a floater only goes to the stores
 * assigned to them, never more floaters at a store than its "max floaters / day". It is a small max-flow, so the
 * answer is exact: "covered" is the most of the stores' minimum staffing those people can fill.
 *
 * <p>Build one per area and week ({@link #of}) and ask it about any day and any set of people off.
 */
public final class Coverage {

    /** How a day looks. {@code shortBy} is how many shifts can't be filled; {@code shortStores} names those stores. */
    public record Day(LocalDate date, int needed, int working, int covered, List<String> shortStores) {

        public int shortBy() {
            return Math.max(0, needed - covered);
        }

        /** People working beyond what the stores need (can be negative). */
        public int spare() {
            return working - needed;
        }
    }

    private final List<Store> stores;
    private final List<Employee> staff;

    private Coverage(List<Store> stores, List<Employee> staff) {
        this.stores = stores.stream().filter(Store::isActive).toList();
        this.staff = List.copyOf(staff);
    }

    /** @param staff everyone who can be scheduled in the area (active managers and employees) */
    public static Coverage of(Collection<Store> stores, Collection<Employee> staff) {
        return new Coverage(List.copyOf(stores), List.copyOf(staff));
    }

    public List<Employee> staff() {
        return staff;
    }

    /** Sum of the stores' minimum staffing on that day. */
    public int needed(LocalDate date) {
        return stores.stream().filter(s -> s.isOpenOn(date)).mapToInt(s -> Math.max(0, s.getMinStaffPerDay())).sum();
    }

    /** The day with everyone in {@code off} away. */
    public Day day(LocalDate date, Set<Long> off) {
        List<Store> open = stores.stream().filter(s -> s.isOpenOn(date)).toList();
        List<Employee> working = staff.stream().filter(e -> !off.contains(e.getId())).toList();
        int needed = open.stream().mapToInt(s -> Math.max(0, s.getMinStaffPerDay())).sum();
        if (open.isEmpty()) {
            return new Day(date, 0, working.size(), 0, List.of());
        }
        // Nodes: 0 source, 1 sink, then a store node and a floater gate per store, then one node per person.
        int n = 2 + open.size() * 2 + working.size();
        Flow flow = new Flow(n);
        Map<Long, Integer> storeNode = new HashMap<>();
        Map<Long, Integer> gateNode = new HashMap<>();
        for (int i = 0; i < open.size(); i++) {
            Store s = open.get(i);
            int node = 2 + i;
            int gate = 2 + open.size() + i;
            storeNode.put(s.getId(), node);
            gateNode.put(s.getId(), gate);
            flow.add(node, 1, Math.max(0, s.getMinStaffPerDay()));
            flow.add(gate, node, s.getMaxFloatersPerDay() > 0 ? s.getMaxFloatersPerDay() : working.size());
        }
        for (int i = 0; i < working.size(); i++) {
            Employee e = working.get(i);
            int node = 2 + open.size() * 2 + i;
            flow.add(0, node, 1);
            for (Long storeId : allowedStores(e, date, open)) {
                // Floaters (no home store) pass through the store's floater limit.
                int target = e.getHomeStore() == null || !e.getHomeStore().isActive() ? gateNode.get(storeId)
                        : storeNode.get(storeId);
                flow.add(node, target, 1);
            }
        }
        int covered = flow.max(0, 1);
        List<String> shortStores = new ArrayList<>();
        for (Store s : open) {
            int filled = flow.flowInto(storeNode.get(s.getId()), 1);
            if (filled < s.getMinStaffPerDay()) {
                shortStores.add(s.getName());
            }
        }
        return new Day(date, needed, working.size(), covered, shortStores);
    }

    /**
     * Whether {@code employeeId} can be off that day on top of {@code off}: the stores are no worse covered without
     * them (a day already short stays as short, it doesn't get shorter).
     */
    public boolean canBeOff(LocalDate date, Set<Long> off, Long employeeId) {
        Day before = day(date, off);
        Set<Long> more = new java.util.HashSet<>(off);
        more.add(employeeId);
        return day(date, more).shortBy() <= before.shortBy();
    }

    /** The stores this person can be placed at on that day (same rules as {@link StoreAssigner}). */
    private static List<Long> allowedStores(Employee e, LocalDate date, List<Store> open) {
        Store home = e.getHomeStore();
        boolean homeOpen = home != null && home.isActive() && open.stream().anyMatch(s -> s.getId().equals(home.getId()));
        if (homeOpen && e.isAnchoredToStore()) {
            return List.of(home.getId());
        }
        Set<Long> floats = e.floatStoreIds();
        List<Long> result = new ArrayList<>();
        for (Store s : open) {
            boolean isHome = home != null && s.getId().equals(home.getId());
            if (isHome || floats == null || floats.isEmpty() || floats.contains(s.getId())) {
                result.add(s.getId());
            }
        }
        return result;
    }

    /** Edmonds–Karp on a tiny graph (a few dozen nodes). */
    private static final class Flow {
        private final int[][] cap;
        private final int[][] used;

        Flow(int n) {
            cap = new int[n][n];
            used = new int[n][n];
        }

        void add(int from, int to, int c) {
            cap[from][to] += c;
        }

        int flowInto(int from, int to) {
            return used[from][to];
        }

        int max(int s, int t) {
            int total = 0;
            int n = cap.length;
            while (true) {
                int[] prev = new int[n];
                java.util.Arrays.fill(prev, -1);
                prev[s] = s;
                ArrayDeque<Integer> q = new ArrayDeque<>();
                q.add(s);
                while (!q.isEmpty() && prev[t] == -1) {
                    int u = q.poll();
                    for (int v = 0; v < n; v++) {
                        if (prev[v] == -1 && cap[u][v] - used[u][v] + used[v][u] > 0) {
                            prev[v] = u;
                            q.add(v);
                        }
                    }
                }
                if (prev[t] == -1) {
                    return total;
                }
                // Unit augment (all paths here carry 1).
                for (int v = t; v != s; v = prev[v]) {
                    int u = prev[v];
                    if (used[v][u] > 0) {
                        used[v][u]--;
                    } else {
                        used[u][v]++;
                    }
                }
                total++;
            }
        }
    }
}
