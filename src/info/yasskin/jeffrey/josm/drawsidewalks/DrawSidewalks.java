package info.yasskin.jeffrey.josm.drawsidewalks;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

public class DrawSidewalks extends Plugin {
  MakeCrossingsAction action;

  /**
   * Will be invoked by JOSM to bootstrap the plugin
   *
   * @param info information about the plugin and its local installation
   */
  public DrawSidewalks(PluginInformation info) {
    super(info);
    action = new MakeCrossingsAction();
    MainMenu.add(MainApplication.getMenu().moreToolsMenu, action);
  }
}
