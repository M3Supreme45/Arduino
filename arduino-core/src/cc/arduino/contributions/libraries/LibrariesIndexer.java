/*
 * This file is part of Arduino.
 *
 * Copyright 2014 Arduino LLC (http://www.arduino.cc/)
 *
 * Arduino is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * As a special exception, you may use this file as part of a free software
 * library without restriction.  Specifically, if other files instantiate
 * templates or use macros or inline functions from this file, or you compile
 * this file and link it with other files to produce an executable, this
 * file does not by itself cause the resulting executable to be covered by
 * the GNU General Public License.  This exception does not however
 * invalidate any other reasons why the executable file might be covered by
 * the GNU General Public License.
 */

package cc.arduino.contributions.libraries;

import static processing.app.I18n.format;
import static processing.app.I18n.tr;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.commons.compress.utils.IOUtils;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.mrbean.MrBeanModule;

import cc.arduino.Constants;
import cc.arduino.contributions.packages.ContributedPlatform;
import processing.app.BaseNoGui;
import processing.app.I18n;
import processing.app.helpers.ProcessUtils;
import processing.app.helpers.filefilters.OnlyDirs;
import processing.app.packages.LegacyUserLibrary;
import processing.app.packages.LibraryList;
import processing.app.packages.UserLibrary;
import processing.app.packages.UserLibraryFolder;
import processing.app.packages.UserLibraryFolder.Location;
import processing.app.packages.UserLibraryPriorityComparator;

public class LibrariesIndexer {

  private LibrariesIndex index;
  private final LibraryList installedLibraries = new LibraryList();
  private List<UserLibraryFolder> librariesFolders;

  private final List<String> badLibNotified = new ArrayList<>();

  public LibrariesIndexer(File preferencesFolder) {
    // TODO: use specified preferencesFolder when running cli
  }

  public void parseIndex() throws IOException {
    index = new EmptyLibrariesIndex(); // Fallback

    Process process = ProcessUtils.exec(new String[] {
        BaseNoGui.getArduinoCliPath(), "lib", "search", "--format", "json" });

    InputStream indexIn = null;
    try {
      indexIn = process.getInputStream();
      ObjectMapper mapper = new ObjectMapper();
      mapper.registerModule(new MrBeanModule());
      mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
      mapper.configure(DeserializationFeature.EAGER_DESERIALIZER_FETCH, true);
      mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
      mapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
      LibrariesIndex newIndex = mapper.readValue(indexIn, LibrariesIndex.class);

      newIndex.getLibraries().forEach(r -> {
        r.getReleases().values().stream()
        .filter(library -> library.getCategory() == null || "".equals(library.getCategory()) || !Constants.LIBRARY_CATEGORIES.contains(library.getCategory()))
        .forEach(library -> library.setCategory("Uncategorized"));
      });

      // Populate ContributedLibraries inner cross-references
      newIndex.getLibraries().forEach(r -> {
        r.getReleases().forEach((v, l) -> {
          l.setReleases(r);
        });
      });
      index = newIndex;
    } catch (NullPointerException e) {
      e.printStackTrace();
    } catch (JsonParseException | JsonMappingException e) {
      System.err.println(
          format(tr("Error parsing libraries index: {0}\nTry to open the Library Manager to update the libraries index."),
              e.getMessage()));
    } catch (Exception e) {
      System.err.println(format(tr("Error reading libraries index: {0}"), e.getMessage()));
    } finally {
      IOUtils.closeQuietly(indexIn);
    }
  }

  public void setLibrariesFolders(List<UserLibraryFolder> folders) {
    librariesFolders = folders;
    rescanLibraries();
  }

  public List<UserLibraryFolder> getLibrariesFolders() {
    return librariesFolders;
  }

  private UserLibraryPriorityComparator priorityComparator = new UserLibraryPriorityComparator(null);

  public void addToInstalledLibraries(UserLibrary lib) {
    UserLibrary toReplace = installedLibraries.getByName(lib.getName());
    if (toReplace == null) {
      installedLibraries.add(lib);
      return;
    }
    if (priorityComparator.compare(toReplace, lib) >= 0) {
      // The current lib has priority, do nothing
      return;
    }
    installedLibraries.remove(toReplace);
    installedLibraries.add(lib);
  }

  public void setArchitecturePriority(String arch) {
    priorityComparator = new UserLibraryPriorityComparator(arch);
  }

  public void rescanLibraries() {
    // Clear all installed flags
    installedLibraries.clear();

    if (index.getLibraries() == null) {
      return;
    }

    index.getLibraries().forEach(r -> {
      r.getReleases().forEach((v, lib) -> lib.unsetInstalledUserLibrary());
    });

    // Rescan libraries
    for (UserLibraryFolder folderDesc : librariesFolders) {
      scanInstalledLibraries(folderDesc);
    }

    installedLibraries.stream() //
        .filter(l -> l.getTypes().contains("Contributed")) //
        .filter(l -> l.getLocation() == Location.CORE || l.getLocation() == Location.REFERENCED_CORE) //
        .forEach(l -> {
          File libFolder = l.getInstalledFolder();
          Optional<ContributedPlatform> platform = BaseNoGui.indexer.getPlatformByFolder(libFolder);
          if (platform.isPresent()) {
            l.setTypes(Collections.singletonList(platform.get().getCategory()));
          }
        });
  }

  private void scanInstalledLibraries(UserLibraryFolder folderDesc) {
    File list[] = folderDesc.folder.listFiles(OnlyDirs.ONLY_DIRS);
    // if a bad folder or something like that, this might come back null
    if (list == null)
      return;

    for (File subfolder : list) {
      String subfolderName = subfolder.getName();
      if (!BaseNoGui.isSanitaryName(subfolderName)) {

        // Detect whether the current folder name has already had a notification.
        if (!badLibNotified.contains(subfolderName)) {

          badLibNotified.add(subfolderName);

          String mess = I18n.format(tr("The library \"{0}\" cannot be used.\n"
              + "Library folder names must start with a letter or number, followed by letters,\n"
              + "numbers, dashes, dots and underscores. Maximum length is 63 characters."),
              subfolderName);
          BaseNoGui.showMessage(tr("Ignoring library with bad name"), mess);
        }
        continue;
      }

      try {
        scanLibrary(new UserLibraryFolder(subfolder, folderDesc.location));
      } catch (IOException e) {
        System.out.println(I18n.format(tr("Invalid library found in {0}: {1}"), subfolder, e.getMessage()));
      }
    }
  }

  private void scanLibrary(UserLibraryFolder folderDesc) throws IOException {
    // A library is considered "legacy" if it doesn't contains
    // a file called "library.properties"
    File check = new File(folderDesc.folder, "library.properties");
    if (!check.exists() || !check.isFile()) {
      // Create a legacy library and exit
      LegacyUserLibrary lib = LegacyUserLibrary.create(folderDesc);
      String[] headers = BaseNoGui.headerListFromIncludePath(lib.getSrcFolder());
      if (headers.length == 0) {
        throw new IOException(format(tr("no headers files (.h) found in {0}"), lib.getSrcFolder()));
      }
      addToInstalledLibraries(lib);
      return;
    }

    // Create a regular library
    UserLibrary lib = UserLibrary.create(folderDesc);
    String[] headers = BaseNoGui.headerListFromIncludePath(lib.getSrcFolder());
    if (headers.length == 0) {
      throw new IOException(format(tr("no headers files (.h) found in {0}"), lib.getSrcFolder()));
    }
    addToInstalledLibraries(lib);

    Location loc = lib.getLocation();
    if (loc != Location.CORE && loc != Location.REFERENCED_CORE) {
      // Check if we can find the same library in the index
      // and mark it as installed
      ContributedLibrary foundLib = index.find(lib.getName(), lib.getVersion());
      if (foundLib != null) {
        foundLib.setInstalledUserLibrary(lib);
        lib.setTypes(foundLib.getTypes());
      }
    }

    if (lib.getTypes().isEmpty() && loc == Location.SKETCHBOOK) {
      lib.setTypes(lib.getDeclaredTypes());
    }

    if (lib.getTypes().isEmpty()) {
      lib.setTypes(Collections.singletonList("Contributed"));
    }
  }

  public LibrariesIndex getIndex() {
    return index;
  }

  public LibraryList getInstalledLibraries() {
    return new LibraryList(installedLibraries);
  }
}
