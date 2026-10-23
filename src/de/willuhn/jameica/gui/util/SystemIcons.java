/**********************************************************************
 *
 * Copyright (c) 2026 Olaf Willuhn
 * All rights reserved.
 *
 * This software is copyrighted work licensed under the terms of the
 * Jameica License.  Please consult the file "LICENSE" for details.
 *
 **********************************************************************/
package de.willuhn.jameica.gui.util;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang.StringUtils;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.internal.DPIUtil;

import de.willuhn.jameica.gui.GUI;
import de.willuhn.jameica.system.Customizing;
import de.willuhn.logging.Logger;

/**
 * Laedt Icons aus dem Icon-Theme des Desktops (Linux mit GTK3).
 * Gesucht wird nur im aktuellen Theme und in den Themes, von denen es erbt, ohne "hicolor".
 * Fehlt ein Icon dort, liefert die Klasse NULL, und der Aufrufer nimmt das mitgelieferte.
 * Die GTK-Funktionen sind in SWT nur intern verfuegbar und werden deshalb per Reflection
 * aufgerufen. Aendert sich SWT dort, bleibt es bei den mitgelieferten Icons.
 */
public final class SystemIcons
{
  /**
   * Customizing-Parameter, mit dem die System-Icons aktiviert werden.
   */
  public static final String KEY = "application.icons.system";

  // GTK_ICON_LOOKUP_FORCE_SIZE | GTK_ICON_LOOKUP_FORCE_REGULAR
  private static final int FLAGS = 16 | 32;

  // Mitgelieferte Icons, die im Theme anders heissen. Genommen wird der erste Name, den das Theme hat.
  // Icons ohne Eintrag werden unter ihrem eigenen Namen gesucht. Die Namen stammen aus der Icon Naming
  // Specification bzw. aus Breeze, wo die Spezifikation keinen passenden kennt. Dateityp-Icons wie
  // "x-office-calendar" sind ungeeignet, weil Themes sie als Dokument zeichnen. Eigene Icons eines Themes
  // stehen vor denen, die es nur erbt. Bei Paaren wie gesperrt/entsperrt stehen nur Namen, die ein Theme
  // meist fuer beide hat, damit nicht eins aus dem Theme kommt und das andere mitgeliefert ist.
  private static final Map<String,List<String>> ALIASES = Map.ofEntries(
    Map.entry("dialog-information-large", List.of("dialog-information")),
    Map.entry("dialog-question-large",    List.of("dialog-question")),
    Map.entry("dialog-warning-large",     List.of("dialog-warning")),
    Map.entry("gtk-info",                 List.of("dialog-information")),
    Map.entry("office-calendar",          List.of("view-calendar","office-calendar")),
    Map.entry("ok",                       List.of("dialog-ok")),
    Map.entry("up",                       List.of("arrow-up","go-up")),
    Map.entry("down",                     List.of("arrow-down","go-down")),
    Map.entry("maximize",                 List.of("arrow-up-double","go-top")),
    Map.entry("minimize",                 List.of("arrow-down-double","go-bottom")),
    Map.entry("locked",                   List.of("object-locked")),
    Map.entry("unlocked",                 List.of("object-unlocked")),
    Map.entry("starred",                  List.of("rating","emblem-favorite")),
    Map.entry("non-starred",              List.of("rating-unrated","bookmark-new")),
    Map.entry("network-transmit-receive", List.of("network-connect","network-transmit-receive")),
    Map.entry("network-offline",          List.of("network-disconnect","network-offline")),
    Map.entry("stock_keyring",            List.of("view-certificate","stock_keyring","application-certificate")),
    Map.entry("emblem-package",           List.of("plugins","emblem-package","application-x-addon")),
    Map.entry("package-x-generic-medium", List.of("preferences-plugin","package-x-generic"))
  );

  private static String theme = null;
  private static Set<String> names = null;
  private static boolean listening = false;

  /**
   * Liefert true, wenn die System-Icons aktiviert und verfuegbar sind.
   * @return true, wenn die System-Icons verwendet werden.
   */
  public static boolean isEnabled()
  {
    return Customizing.SETTINGS.getBoolean(KEY,false) && getTheme() != null;
  }

  /**
   * Liefert den Namen des aktuellen Icon-Themes.
   * @return Name des Themes oder NULL, wenn es nicht ermittelt werden kann.
   */
  public static synchronized String getTheme()
  {
    if (theme != null)
      return theme;

    if (!"gtk".equals(SWT.getPlatform()))
      return null;

    try
    {
      if (gtk().getField("GTK4").getBoolean(null))
        return null;

      long settings = (long) gtk().getMethod("gtk_settings_get_default").invoke(null);
      long[] ptr = new long[1];
      os().getMethod("g_object_get",long.class,byte[].class,long[].class,long.class).invoke(null,settings,mbcs("gtk-icon-theme-name"),ptr,0L);
      if (ptr[0] == 0)
        return null;

      theme = (String) converter().getMethod("cCharPtrToJavaString",long.class,boolean.class).invoke(null,ptr[0],true);
      Logger.info("system icon theme: " + theme);
    }
    catch (Throwable t)
    {
      Logger.warn("unable to determine system icon theme: " + t);
    }
    return theme;
  }

  /**
   * Liefert das Icon aus dem System-Theme.
   * @param filename Dateiname des mitgelieferten Icons, z.B. "document-save.png".
   * @param size Kantenlaenge in Pixeln.
   * @return die Bilddaten oder NULL, wenn das Theme kein passendes Icon hat.
   */
  public static synchronized ImageData getImageData(String filename, int size)
  {
    if (filename == null || !filename.endsWith(".png") || size <= 0)
      return null;

    String bundled = filename.substring(0,filename.length() - 4);
    String name = ALIASES.getOrDefault(bundled,List.of(bundled)).stream().filter(getNames()::contains).findFirst().orElse(null);
    if (name == null)
      return null;

    try
    {
      listen();
      long iconTheme = (long) gtk3().getMethod("gtk_icon_theme_get_default").invoke(null);
      Method load = gtk3().getMethod("gtk_icon_theme_load_icon",long.class,byte[].class,int.class,int.class,long.class);
      long pixbuf = (long) load.invoke(null,iconTheme,mbcs(name),size,FLAGS,0L);
      if (pixbuf == 0)
        return null;

      Method convert = Image.class.getMethod("gtk_new_from_pixbuf",Device.class,int.class,long.class);
      Image image = (Image) convert.invoke(null,GUI.getDisplay(),SWT.BITMAP,pixbuf);
      os().getMethod("g_object_unref",long.class).invoke(null,pixbuf);
      try
      {
        // Das Image enthaelt die Pixel des Pixbuf in der aktuellen Zoom-Stufe, getImageData() wuerde sie auf 100% herunterrechnen
        return image.getImageData(DPIUtil.getDeviceZoom());
      }
      finally
      {
        image.dispose();
      }
    }
    catch (Throwable t)
    {
      Logger.warn("unable to load system icon " + name + ": " + t);
      return null;
    }
  }

  /**
   * Liefert die Namen aller Icons im aktuellen Theme und den Themes, von denen es erbt.
   * @return Namen der Icons ohne Endung.
   */
  private static Set<String> getNames()
  {
    if (names != null)
      return names;

    names = new HashSet<String>();
    List<String> themes = new ArrayList<String>();
    String t = getTheme();
    if (t != null)
      themes.add(t);

    for (int i=0;i<themes.size();++i)
    {
      for (File dir:getThemeDirs(themes.get(i)))
      {
        for (String parent:getInherits(dir))
        {
          if (!parent.equals("hicolor") && !themes.contains(parent))
            themes.add(parent);
        }
        try (Stream<Path> files = Files.walk(dir.toPath()))
        {
          files.map(p -> p.getFileName().toString())
               .filter(f -> f.endsWith(".svg") || f.endsWith(".png"))
               .forEach(f -> names.add(f.substring(0,f.length() - 4)));
        }
        catch (IOException e)
        {
          Logger.warn("unable to read icon theme " + dir + ": " + e);
        }
      }
    }
    Logger.info("system icon themes " + themes + ": " + names.size() + " icons");
    return names;
  }

  /**
   * Liefert die Verzeichnisse eines Themes gemaess der Icon Theme Specification.
   * @param name Name des Themes.
   * @return Liste der vorhandenen Verzeichnisse.
   */
  private static List<File> getThemeDirs(String name)
  {
    String home = System.getProperty("user.home");
    String dataDirs = StringUtils.defaultIfBlank(System.getenv("XDG_DATA_HOME"),home + "/.local/share") + ":" +
                      StringUtils.defaultIfBlank(System.getenv("XDG_DATA_DIRS"),"/usr/local/share:/usr/share");

    List<File> result = new ArrayList<File>();
    result.add(new File(home + "/.icons",name));
    for (String d:dataDirs.split(":"))
      result.add(new File(d + "/icons",name));
    result.removeIf(f -> !f.isDirectory());
    return result;
  }

  /**
   * Liefert die Themes, von denen das Theme erbt.
   * @param dir Verzeichnis des Themes.
   * @return Namen aus der Zeile "Inherits" der Datei index.theme.
   */
  private static List<String> getInherits(File dir)
  {
    try
    {
      return Files.readAllLines(new File(dir,"index.theme").toPath()).stream()
                  .filter(line -> line.startsWith("Inherits="))
                  .flatMap(line -> Arrays.stream(line.substring(9).split(",")))
                  .map(String::trim)
                  .filter(parent -> !parent.isEmpty())
                  .collect(Collectors.toList());
    }
    catch (IOException e)
    {
      // Ohne lesbare index.theme erbt das Theme von keinem anderen
      return List.of();
    }
  }

  /**
   * Meldet sich fuer Aenderungen der Desktop-Einstellungen an, etwa beim Wechsel zwischen hellem
   * und dunklem Theme. Dann werden Theme und Icons neu ermittelt.
   */
  private static void listen()
  {
    if (listening)
      return;
    listening = true;
    GUI.getDisplay().addListener(SWT.Settings,e -> {
      String old = theme;
      synchronized (SystemIcons.class)
      {
        theme = null;
        names = null;
      }
      if (old != null && !old.equals(getTheme()))
        SWTUtil.clearImageCache();
    });
  }

  private static Class gtk() throws ClassNotFoundException
  {
    return Class.forName("org.eclipse.swt.internal.gtk.GTK");
  }

  private static Class gtk3() throws ClassNotFoundException
  {
    return Class.forName("org.eclipse.swt.internal.gtk3.GTK3");
  }

  private static Class os() throws ClassNotFoundException
  {
    return Class.forName("org.eclipse.swt.internal.gtk.OS");
  }

  private static Class converter() throws ClassNotFoundException
  {
    return Class.forName("org.eclipse.swt.internal.Converter");
  }

  private static byte[] mbcs(String s) throws Exception
  {
    return (byte[]) converter().getMethod("wcsToMbcs",String.class,boolean.class).invoke(null,s,true);
  }
}
