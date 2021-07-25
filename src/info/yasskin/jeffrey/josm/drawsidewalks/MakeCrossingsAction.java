package info.yasskin.jeffrey.josm.drawsidewalks;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SplitWayCommand;
import org.openstreetmap.josm.data.osm.DefaultNameFormatter;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Tagged;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Select a sidewalk way, and run this action, and it'll make all segments that
 * cross or end at roadways into crossings.
 *
 * Be sure to have first run Selection|Intersecting ways (I) and Add nodes at
 * Intersections (Shift-I) to ensure intersections are marked.
 */
public class MakeCrossingsAction extends JosmAction {
  MakeCrossingsAction() {
    super(tr("Split sidewalk and crossings"), "extract-sidewalk", tr("Split sidewalk and crossings"),
        Shortcut.registerShortcut("edit:splitcrossings", tr("Data: {0}", tr("split crossings")), KeyEvent.VK_C,
            Shortcut.ALT_SHIFT),
        true);
    setEnabled(false);
  }

  @Override
  protected void updateEnabledState() {
    updateEnabledStateOnCurrentSelection();
  }

  @Override
  protected void updateEnabledState(Collection<? extends OsmPrimitive> selection) {
    setEnabled(selection != null && selection.size() == 1 && allCouldBeSidewalks(selection));
  }

  boolean allCouldBeSidewalks(Collection<? extends OsmPrimitive> selection) {
    return selection.stream().allMatch(
        way -> way.getType() == OsmPrimitiveType.WAY && (!way.hasKey("highway") || way.hasTag("highway", "footway")));
  }

  /** Which values for the `highway` key mean cars will be driving here? */
  static final Set<String> ROADWAYS = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
          "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street")));

  @Override
  public void actionPerformed(ActionEvent e) {
    if (!isEnabled()) {
      return;
    }
    try (UndoRedoBuilder commands = new UndoRedoBuilder();) {
      Collection<OsmPrimitive> selection = Objects.requireNonNull(getLayerManager().getEditDataSet()).getSelected();
      Optional<OsmPrimitive> selected = getOnly(selection);
      assert selected.isPresent();
      Way way = (Way) selected.get();

      final List<Node> kerbs = new ArrayList<>();
      final Set<Node> intersections = new HashSet<>();
      findKerbsAroundIntersections(way, kerbs, intersections);
      Logging.debug("Intersections between way {0}\nand roads: {1}", way, intersections);
      SplitWayCommand splitCommand = SplitWayCommand.split(way, kerbs, selection);
      commands.add(splitCommand);
      commands.add(new ChangePropertyCommand(intersections, "highway", "crossing"));
      setSplitWayTags(splitCommand, intersections, commands::add);
      commands.commit(tr("Make crossings along sidewalk {0}", way.getDisplayName(DefaultNameFormatter.getInstance())));
    }
  }

  /**
   *
   * @param way           The overall sidewalk way.
   * @param kerbs         Filled with the "kerb" nodes where crossings and
   *                      sidewalks alternate.
   * @param intersections Filled with |way|'s intersections with roadways.
   */
  private void findKerbsAroundIntersections(Way way, final List<Node> kerbs, final Set<Node> intersections) {
    final int nodeCount = way.getNodesCount();
    for (int i = 0; i < nodeCount; i++) {
      Node node = way.getNode(i);
      List<Way> intersectingWays = node.getParentWays();
      intersectingWays.remove(way);
      if (intersectingWays.stream().anyMatch(way2 -> ROADWAYS.contains(way2.get("highway")))) {
        intersections.add(node);
        // Split the way at the nodes just before and after this intersection.
        if (i != 0) {
          kerbs.add(way.getNode(i - 1));
        }
        if (i < nodeCount - 1) {
          kerbs.add(way.getNode(i + 1));
        }
      }
    }
  }

  void setSplitWayTags(SplitWayCommand splitCommand, Set<Node> intersections, Consumer<Command> commands) {
    for (Way splitWay : splitCommand.getNewWays()) {
      // FIXME: Also run for the original way.

      Map<String, String> splitWayTagChanges = new HashMap<>();
      splitWayTagChanges.put("highway", "footway");
      List<Node> crossings = splitWay.getNodes().stream().filter(intersections::contains).collect(Collectors.toList());
      if (crossings.isEmpty()) {
        // It's a sidewalk.
        splitWayTagChanges.put("footway", "sidewalk");
      } else {
        // It's a crossing.
        splitWayTagChanges.put("footway", "crossing");
        getOnly(crossings).ifPresent(crossingNode -> {
          // Copy some tags from the crossing node to the crossing way. If
          // there's more than one crossing, it's less clear what to do, so we
          // leave the way alone.

          // `tactile_paving=yes` means both kerbs have tactile paving, but =no doesn't
          // reliably mean that neither has it.
          if (crossingNode.hasTag("tactile_paving", "yes")) {
            // The overall way might begin or end at this crossing, in which case it's not
            // actually a kerb. But in that case it also doesn't hurt to set
            // tactile_paving=yes redundantly.
            commands.accept(new ChangePropertyCommand(Arrays.asList(splitWay.firstNode(), splitWay.lastNode()),
              "tactile_paving", "yes"));
          }

          // The kind of crossing, and whether it has an island, are shared between the
          // node and the way.
          copyTag(crossingNode, "crossing", splitWayTagChanges);
          copyTag(crossingNode, "crossing:island", splitWayTagChanges);
        });

        // Set the crossing's surface if all intersecting ways agree on the same
        // surface. Clear it otherwise.
        Logging.debug("Finding intersecting surfaces for {0}", splitWay);
        Set<Way> intersectingWays = new HashSet<>(crossings.size() + 1);
        crossings.forEach(crossing -> intersectingWays.addAll(crossing.getParentWays()));
        intersectingWays.remove(splitCommand.getOriginalWay());
        intersectingWays.remove(splitWay);
        Logging.debug("Intersecting ways: {0}", intersectingWays);
        Set<String> surfaces = intersectingWays.stream().map(way -> way.get("surface")).collect(Collectors.toSet());
        Logging.debug("All surfaces {0}", surfaces);
        Optional<String> surface = getOnly(surfaces);
        Logging.debug("Picked surface {0}", surface);
        splitWayTagChanges.put("surface", surface.orElse(null));

        // Clear the smoothness for the crossing, since there's no indication
        // it'll be similar to either the smoothness of the rest of the sidewalk or the
        // rest of any of the streets.
        splitWayTagChanges.put("smoothness", null);
      }
      commands.accept(new ChangePropertyCommand(Collections.singleton(splitWay), splitWayTagChanges));
    }
  }

  void copyTag(Tagged source, String key, Map<String, String> dest) {
    if (source.hasTag(key)) {
      dest.put(key, source.get(key));
    }
  }

  <T> Optional<T> first(Collection<T> col) {
    if (col.isEmpty())
      return Optional.empty();
    return Optional.ofNullable(col.iterator().next());
  }

  <T> Optional<T> last(List<T> list) {
    if (list.isEmpty())
      return Optional.empty();
    return Optional.ofNullable(list.get(list.size() - 1));
  }

  <T> Optional<T> getOnly(Collection<T> col) {
    if (col.size() != 1)
      return Optional.empty();
    return Optional.ofNullable(col.iterator().next());
  }
}
