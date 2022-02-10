/*
 * Copyright © 2018-2021, Commonwealth Scientific and Industrial Research Organisation (CSIRO) ABN 41 687 119 230.
 * Licensed under the CSIRO Open Source Software Licence Agreement.
 */
package au.csiro.redmatch.validation;

import au.csiro.redmatch.model.VersionedFhirPackage;
import ca.uhn.fhir.context.FhirContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.CodeSystem.*;
import org.hl7.fhir.r4.model.ElementDefinition.TypeRefComponent;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Creates a code system from the FHIR metadata that can be used for validation and searching.
 *
 * @author Alejandro Metke-Jimenez
 *
 */
public class RedmatchGrammarCodeSystemGenerator {

  private static final Log log = LogFactory.getLog(RedmatchGrammarCodeSystemGenerator.class);

  private final Gson gson;
  private final FhirContext ctx;

  private final Map<String, StructureDefinition> structureDefinitionsMapByCode = new HashMap<>();
  // Used to process profiled extensions
  private final Map<String, StructureDefinition> structureDefinitionsMapByUrl = new HashMap<>();
  private final Set<String> allCodes = new HashSet<>();

  public RedmatchGrammarCodeSystemGenerator(Gson gson, FhirContext ctx) {
    this.gson = gson;
    this.ctx = ctx;
  }

  /**
   * Creates a validation code system for a version of FHIR or an implementation guide.
   *
   * @param fhirPackage The NPM package of a version of FHIR or implementation guide, e.g., hl7.fhir.r4.core.
   * @return A code system that can be used for validation.
   * @throws IOException If there are issues reading the files.
   */
  public CodeSystem createCodeSystem(VersionedFhirPackage fhirPackage) throws IOException {

    // Create a set with all the FHIR packages required
    Set<VersionedFhirPackage> packages = new HashSet<>();
    packages.add(fhirPackage);
    packages.addAll(getDependencies(fhirPackage));

    for(VersionedFhirPackage pack : packages) {
      getStructureDefinitions(pack).forEach(e -> {
        structureDefinitionsMapByCode.put(e.getId().replace("StructureDefinition/", ""), e);
        structureDefinitionsMapByUrl.put(e.getUrl(), e);
      });
    }

    Set<StructureDefinition> complexTypes = structureDefinitionsMapByCode.values().stream().filter(e ->
      e.hasDerivation() && e.getDerivation().equals(StructureDefinition.TypeDerivationRule.SPECIALIZATION)
        && e.hasKind() && e.getKind().equals(StructureDefinition.StructureDefinitionKind.COMPLEXTYPE)
    ).collect(Collectors.toSet());

    log.info("Found " + complexTypes.size() + " complex types");

    Set<StructureDefinition> resourceProfiles = structureDefinitionsMapByCode.values().stream().filter(e ->
      e.hasDerivation() && e.getDerivation().equals(StructureDefinition.TypeDerivationRule.CONSTRAINT)
        && e.hasKind() && e.getKind().equals(StructureDefinition.StructureDefinitionKind.RESOURCE)
    ).collect(Collectors.toSet());

    log.info("Found " + resourceProfiles.size() + " resource profiles");

    Set<StructureDefinition> resources = structureDefinitionsMapByCode.values().stream().filter(e ->
      e.hasDerivation() && e.getDerivation().equals(StructureDefinition.TypeDerivationRule.SPECIALIZATION)
        && e.hasKind() && e.getKind().equals(StructureDefinition.StructureDefinitionKind.RESOURCE)
    ).collect(Collectors.toSet());

    log.info("Found " + resources.size() + " resources");

    CodeSystem codeSystem = createBaseCodeSystem(fhirPackage);

    for (StructureDefinition structureDefinition : complexTypes) {
      processStructureDefinition(codeSystem, structureDefinition, false, "");
    }

    for (StructureDefinition structureDefinition : resourceProfiles) {
      processStructureDefinition(codeSystem, structureDefinition, false, "");
    }

    for (StructureDefinition structureDefinition : resources) {
      processStructureDefinition(codeSystem, structureDefinition, false, "");
    }

    return codeSystem;
  }

  private CodeSystem createBaseCodeSystem(VersionedFhirPackage fhirPackage) {
    CodeSystem cs = new CodeSystem();
    cs.setId("redmatch-"+fhirPackage.getName());
    cs.setUrl("http://redmatch."+fhirPackage.getName());
    cs.setVersion(fhirPackage.getVersion());
    cs.setName("Redmatch Validation Code System for " + fhirPackage.getName());
    cs.setStatus(PublicationStatus.ACTIVE);
    cs.setDescription("A code system with all the valid paths used to refer to attributes of resources in "
      + fhirPackage.getName());
    cs.setValueSet("http://redmatch."+fhirPackage.getName()+"?vs");
    cs.setHierarchyMeaning(CodeSystemHierarchyMeaning.ISA);
    cs.setContent(CodeSystemContentMode.COMPLETE);
    cs.setExperimental(false);
    cs.setCompositional(false);
    cs.setVersionNeeded(false);
    cs.addProperty()
      .setCode("parent")
      .setDescription("Parent codes.")
      .setType(PropertyType.CODE);
    cs.addProperty()
      .setCode("root")
      .setDescription("Indicates if this concept is a root concept.")
      .setType(PropertyType.BOOLEAN);
    cs.addProperty()
      .setCode("deprecated")
      .setDescription("Indicates if this concept is deprecated.")
      .setType(PropertyType.BOOLEAN);
    cs.addProperty()
      .setCode("min")
      .setDescription("Minimum cardinality")
      .setType(PropertyType.INTEGER);
    cs.addProperty()
      .setCode("max")
      .setDescription("Maximum cardinality")
      .setType(PropertyType.STRING);
    cs.addProperty()
      .setCode("type")
      .setDescription("Data type for this element.")
      .setType(PropertyType.STRING);
    cs.addProperty()
      .setCode("targetProfile")
      .setDescription("If this code represents a Reference attribute, contains an allowed target profile.")
      .setType(PropertyType.STRING);
    cs.addFilter()
      .setCode("root")
      .setValue("True or false.")
      .addOperator(FilterOperator.EQUAL);
    cs.addFilter()
      .setCode("deprecated")
      .setValue("True or false.")
      .addOperator(FilterOperator.EQUAL);

    // Create root concept
    ConceptDefinitionComponent objectRoot = cs.addConcept()
      .setCode("Object")
      .setDisplay("Object");
    objectRoot.addProperty().setCode("root").setValue(new BooleanType(true));
    objectRoot.addProperty().setCode("deprecated").setValue(new BooleanType(false));

    ConceptDefinitionComponent complexTypeRoot = cs.addConcept()
      .setCode("ComplexType")
      .setDisplay("ComplexType");
    complexTypeRoot.addProperty().setCode("root").setValue(new BooleanType(false));
    complexTypeRoot.addProperty().setCode("deprecated").setValue(new BooleanType(false));
    complexTypeRoot.addProperty().setCode("parent").setValue(new CodeType("Object"));

    ConceptDefinitionComponent resourceRoot = cs.addConcept()
      .setCode("Resource")
      .setDisplay("Resource");
    resourceRoot.addProperty().setCode("root").setValue(new BooleanType(false));
    resourceRoot.addProperty().setCode("deprecated").setValue(new BooleanType(false));
    resourceRoot.addProperty().setCode("parent").setValue(new CodeType("Object"));

    ConceptDefinitionComponent profileRoot = cs.addConcept()
      .setCode("Profile")
      .setDisplay("Profile");
    profileRoot.addProperty().setCode("root").setValue(new BooleanType(false));
    profileRoot.addProperty().setCode("deprecated").setValue(new BooleanType(false));
    profileRoot.addProperty().setCode("parent").setValue(new CodeType("Object"));

    return cs;
  }

  private Set<VersionedFhirPackage> getDependencies(VersionedFhirPackage fhirPackage) throws IOException {
    File mainPackageFile = getFolderForFhirPackage(fhirPackage).resolve("package.json").toFile();

    if (!mainPackageFile.exists()) {
      log.debug("Package is not available locally, so will try to install");
      installPackage(fhirPackage);
    }
    if (!mainPackageFile.canRead()) {
      throw new IOException("Package file " + mainPackageFile + " could not be read.");
    }

    Set<VersionedFhirPackage> res = new HashSet<>();
    try (FileReader fr = new FileReader(mainPackageFile)) {
      JsonObject mainPackage = gson.fromJson(fr, JsonObject.class);
      if (mainPackage.has("dependencies")) {
        JsonObject dependencies = mainPackage.getAsJsonObject("dependencies");
        for (String key : dependencies.keySet()) {
          VersionedFhirPackage dependency = new VersionedFhirPackage(key, dependencies.get(key).getAsString());
          res.add(dependency);
          res.addAll(getDependencies(dependency));
        }
      }
    }
    return res;
  }

  private List<StructureDefinition> getStructureDefinitions(VersionedFhirPackage fhirPackage) throws IOException {
    try (Stream<Path> paths = Files.walk(Paths.get(
      System.getProperty("user.home"),
      ".fhir",
      "packages",
      fhirPackage.toString(),
      "package"
    ))) {
      return paths
        .filter(Files::isRegularFile)
        .map(Path::toFile)
        .filter(f -> f.getName().endsWith(".json"))
        .filter(f -> f.getName().startsWith("StructureDefinition"))
        .filter(f -> {
          try (FileReader reader = new FileReader(f)) {
            StructureDefinition structureDefinition = (StructureDefinition) ctx.newJsonParser().parseResource(reader);
            return structureDefinition.hasSnapshot();
          } catch (Exception e) {
            log.warn("There was a problem with " + f.getName(), e);
            return false;
          }
        })
        .map(f -> {
          try (FileReader reader = new FileReader(f)) {
            return (StructureDefinition) ctx.newJsonParser().parseResource(reader);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        })
        .filter(s -> !s.getAbstract())
        .collect(Collectors.toList());
    }
  }

  private boolean isValueX(ElementDefinition ed) {
    return ed.getPath().endsWith("[x]");
  }

  /**
   * Creates codes for all the valid paths of a structure definition.
   *
   * @param codeSystem The code system.
   * @param structureDefinition The structure definition.
   * @param nested True if this is being processed as an attribute of another structure definition.
   * @param prefix If nested is true, then this contains the base path for all the paths in this
   * structure definition.
   */
  private void processStructureDefinition(CodeSystem codeSystem, StructureDefinition structureDefinition,
                                          boolean nested, String prefix) {

    if (structureDefinition.getName().equals("Extension")) {
      return;
    }

    // Keeps track of the parent paths of the elements, e.g. Observation.component
    final Deque<String> parents = new ArrayDeque<>();
    parents.push("");

    Set<String> prefixesToIgnore = new HashSet<>();

    for (ElementDefinition elementDefinition : structureDefinition.getSnapshot().getElement()) {
      // Special case: we ignore the root node if this is a nested element
      if (nested && !elementDefinition.hasType()) {
        continue;
      }

      boolean isValueX = isValueX(elementDefinition);

      if (isValueX) {
        // Process for each type, replacing path with actual type
        for (TypeRefComponent typeRefComponent : elementDefinition.getType()) {
          String path = calculatePath(structureDefinition, elementDefinition, nested);
          if (path != null) {
            path = removeX(path) + capitaliseFirst(typeRefComponent.getCode());
            processElementDefinition(codeSystem, structureDefinition, elementDefinition, nested, prefix, parents, path,
              typeRefComponent, prefixesToIgnore);
          }
        }
      } else {
        String path = calculatePath(structureDefinition, elementDefinition, nested);
        if (path != null) {
          processElementDefinition(codeSystem, structureDefinition, elementDefinition, nested, prefix, parents, path,
            null, prefixesToIgnore);
        }
      }
    }
  }

  private void processElementDefinition(CodeSystem codeSystem, StructureDefinition structureDefinition,
                                        ElementDefinition elementDefinition, boolean nested, String prefix,
                                        Deque<String> parents, String path, TypeRefComponent typeRefComponent,
                                        Set<String> prefixesToIgnore) {
    log.debug("Processing element definition " + elementDefinition.getPath());

    if ("0".equals(elementDefinition.getMax())) {
      log.info("Element has a max value of 0 and will therefore be excluded from the code system.");
      prefixesToIgnore.add(path);
      return;
    }

    boolean[] ignore = new boolean[1];
    prefixesToIgnore.forEach(p -> {
      if (path.startsWith(removeAllBrackets(p))) {
        log.info("Parent element was excluded, so excluding this element.");
        ignore[0] = true;
      }
    });

    if(ignore[0]) {
      return;
    }

    log.debug("Parent are: " + parents);

    // Look for the right fullParent
    String parent = parents.peek();
    if (!path.isEmpty()) {
      while (true) {
        assert parent != null;
        if (path.startsWith(removeAllBrackets(parent))) break;
        parents.pop();
        parent = parents.peek();
      }
    }
    log.debug("Parent is: " + parent);

    String fullParent;
    if (prefix != null && !prefix.isEmpty()) {
      if (parent != null && !parent.isEmpty()) {
        fullParent = prefix + "." + parent;
      } else {
        fullParent = prefix;
      }
    } else {
      fullParent = parent;
    }
    log.debug("Full parent is: " + fullParent);

    if (typeRefComponent == null) {
      typeRefComponent = elementDefinition.getTypeFirstRep();
      if (elementDefinition.getType().size() > 1) {
        throw new RuntimeException("Found non-x-value with more than one type: " + elementDefinition);
      }
    }
    log.debug("Element definition type: " + typeRefComponent);

    String typeCode = typeRefComponent.getCode();
    List<CanonicalType> typeProfiles = typeRefComponent.getProfile();
    if (typeProfiles.size() > 1) {
      log.warn("Path " + path + " has more than one profile: " + typeProfiles);
    }

    // Create code in code system
    String code = prefix + (nested ? "." : "") +  path;

    log.debug("Creating code " + code);
    if (!allCodes.add(removeAllBrackets(code))) {
      log.warn("Duplicate code found " + removeAllBrackets(code));
      return;
    }

    ConceptDefinitionComponent cdc =
      codeSystem.addConcept()
        .setCode(removeAllBrackets(code))
        .setDisplay(removeAllBrackets(code));
    cdc.addProperty().setCode("min").setValue(new IntegerType(elementDefinition.getMin()));
    cdc.addProperty().setCode("max").setValue(new StringType(elementDefinition.getMax()));
    if (fullParent != null && !fullParent.isEmpty()) {
      cdc.addProperty().setCode("parent").setValue(new CodeType(removeAllBrackets(fullParent)));
    } else {
      if (isComplexType(structureDefinition)) {
        cdc.addProperty().setCode("parent").setValue(new CodeType("ComplexType"));
      } else if (isProfile(structureDefinition)) {
        cdc.addProperty().setCode("parent").setValue(new CodeType("Profile"));
      } else {
        cdc.addProperty().setCode("parent").setValue(new CodeType("Resource"));
      }
    }
    cdc.addProperty().setCode("root").setValue(new BooleanType(false));
    cdc.addProperty().setCode("deprecated").setValue(new BooleanType(false));

    // Add synonyms
    addSynonym(code, cdc);
    addSynonyms(code, 0, cdc);

    if (typeCode != null) {
      cdc.addProperty().setCode("type").setValue(new StringType(typeCode));
    } else {
      log.warn("No type found for code " + code);
    }

    if ("Reference".equals(typeCode)) {
      for (CanonicalType targetProfile : typeRefComponent.getTargetProfile()) {
        String targetProfileUrl = targetProfile.getValueAsString();
        StructureDefinition targetStructureDefinition = structureDefinitionsMapByUrl.get(targetProfileUrl);
        if (targetStructureDefinition != null) {
          cdc.addProperty()
            .setCode("targetProfile")
            .setValue(new StringType(targetStructureDefinition.getName()));
        } else {
          log.warn("Could not find structure definition for target profile " + targetProfileUrl);
          cdc.addProperty()
            .setCode("targetProfile")
            .setValue(new StringType(targetProfileUrl));
        }
      }
    } else {
      String newPrefix = null;
      if (prefix != null) {
        newPrefix = prefix + (prefix.isEmpty() ? "" : ".") + path;
      }
      if ("Extension".equals(typeCode) && !typeProfiles.isEmpty()) {
        // Special case: profiled extensions
        String extensionUrl = typeProfiles.get(0).getValue();
        StructureDefinition extensionStructureDefinition = structureDefinitionsMapByUrl.get(extensionUrl);
        if (extensionStructureDefinition == null) {
          log.error("Could not find extension: " + extensionUrl);
          return;
        }
        assert newPrefix != null;
        processStructureDefinition(codeSystem, extensionStructureDefinition, true, newPrefix);
      } else if (!"Resource".equals(typeCode) && isComplexType(typeRefComponent)) {
        log.debug("Processing type recursively: " + typeCode);
        // TODO: the complex type might be profiled
        StructureDefinition nestedStructureDefinition = getComplexType(typeRefComponent);
        assert newPrefix != null;
        processStructureDefinition(codeSystem, nestedStructureDefinition, true, newPrefix);
      }
    }

    // Add path to parents if required
    log.debug("Adding to parents if required");
    assert fullParent != null;
    String pathNoBrackets = removeAllBrackets(path);
    log.debug("Path no brackets: " + pathNoBrackets);
    String fullParentNoBrackets = removeAllBrackets(fullParent);
    log.debug("Full parent no brackets: " + fullParentNoBrackets);
    if (pathNoBrackets.length() > fullParentNoBrackets.length()
      && pathNoBrackets.startsWith(fullParentNoBrackets)) {
      parents.push(path);
    }
  }

  /**
   * Calculates the path for an element definition, adding brackets where the multiplicity is greater than one. If it is
   * nested then it drops the base type. If this is a profile then it replaces the resource / type name with the profile
   * name.
   *
   * @param structureDefinition The structure definition that contains the element definition.
   * @param elementDefinition The element definition.
   * @param nested Flag that indicates if this is nested.
   * @return The path or null of the path in the element definition is null.
   */
  private String calculatePath(StructureDefinition structureDefinition, ElementDefinition elementDefinition,
                               boolean nested) {
    String path = elementDefinition.getPath();
    if (path == null) {
      return null;
    }

    // If the structure definition is a profile, then we replace the path with the profile name (rather than the
    // resource name)
    if (isProfile(structureDefinition)) {
      int index = path.indexOf('.');
      if (index == -1) {
        //path = structureDefinition.getId().replace("StructureDefinition/", "");
        path = structureDefinition.getName();
      } else {
        //path = structureDefinition.getId().replace("StructureDefinition/", "") + path.substring(index);
        path = structureDefinition.getName() + path.substring(index);
      }
    }

    if (nested) {
      path = discardBaseType(path);
    }

    if (path.contains(".")) {
      String max = elementDefinition.getMax();
      if (!"1".equals(max)) {
        path = path + "[]";
      }
    }

    // Special case: if this is a profiled extension, then we replace the 'extension' path element with the slice name
    if (elementDefinition.hasType() && elementDefinition.hasSliceName()) {
      TypeRefComponent typeRef = elementDefinition.getTypeFirstRep();
      if ("Extension".equals(typeRef.getCode()) && !typeRef.getProfile().isEmpty() && path.endsWith(".extension")) {
        path = path.substring(0, path.length() - 9) + elementDefinition.getSliceName();
        log.debug("Replaced 'extension' element in path: " + path);
      }
    }

    return path;
  }

  private boolean isProfile(StructureDefinition structureDefinition) {
    return structureDefinition.getDerivation().equals(StructureDefinition.TypeDerivationRule.CONSTRAINT);
  }

  private boolean isComplexType(StructureDefinition structureDefinition) {
    return structureDefinition.getKind().equals(StructureDefinition.StructureDefinitionKind.COMPLEXTYPE);
  }

  private String discardBaseType(String path) {
    int dotIndex = path.indexOf(".");
    if (dotIndex == -1) {
      return "";
    } else {
      return path.substring(dotIndex + 1);
    }
  }

  private String removeAllBrackets (String s) {
    return s.replace("[]", "");
  }

  private void addSynonyms (String code, int start, ConceptDefinitionComponent cdc) {
    int index = code.indexOf("[]", start);
    if (index == -1) {
      return;
    }

    addSynonyms(code, index + 2, cdc);
    String newCode = code.substring(0, index) +
      ((index + 2) <= code.length() ? code.substring(index + 2) : "");
    addSynonyms(newCode, index, cdc);

    // The code with no brackets is the display so no need to add
    if (newCode.contains("[]")) {
      addSynonym(newCode, cdc);
    }
  }

  private void addSynonym (String code, ConceptDefinitionComponent cdc) {
    cdc.addDesignation()
      .setValue(code)
      .getUse()
      .setSystem("http://snomed.info/sct")
      .setCode("900000000000013009")
      .setDisplay("Synonym (core metadata concept)");
  }

  private boolean isComplexType(TypeRefComponent trc) {
    String code = trc.getCode();

    StructureDefinition structureDefinition = structureDefinitionsMapByCode.get(code);
    if (structureDefinition != null) {
      switch(structureDefinition.getKind()) {
        case PRIMITIVETYPE:
          return false;
        case COMPLEXTYPE:
        case RESOURCE:
          return true;
        case LOGICAL:
        case NULL:
          throw new RuntimeException("Unexpected structure definition kind '" + structureDefinition.getKind() + "' for "
            + structureDefinition.getId());
      }
    } else if (code == null || "Element".equals(code) || "BackboneElement".equals(code)
      || code.startsWith("http://hl7.org/fhirpath/")) {
      return false;
    } else {
      throw new RuntimeException("Unexpected type " + code);
    }
    return false;
  }

  private StructureDefinition getComplexType(TypeRefComponent trc) {
    String code = trc.getCode();
    if (structureDefinitionsMapByCode.containsKey(code)) {
      return structureDefinitionsMapByCode.get(code);
    } else {
      throw new RuntimeException("Unexpected type " + code);
    }
  }

  private String removeX(String path) {
    return path.replace("[x]", "");
  }

  private String capitaliseFirst(String s) {
    if (s.isEmpty()) {
      return s;
    }
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  /**
   * Attempts to download and install a FHIR package in the local machine if it doesn't exist.
   *
   * @param fhirPackage The FHIR package.
   */
  private void installPackage(VersionedFhirPackage fhirPackage) throws FhirPackageNotFoundException {
    log.info("Installing FHIR package " + fhirPackage);
    try {
      URL url = new URL("https", "packages.simplifier.net", "/" + fhirPackage.getName() + "/"
        + fhirPackage.getVersion());
      ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());

      // Decompress in target folder
      File outputDir = getFolderForFhirPackage(fhirPackage).toFile().getParentFile();
      try(InputStream is = Channels.newInputStream(readableByteChannel)) {
        try(GZIPInputStream in = new GZIPInputStream(is)) {
          try (TarArchiveInputStream debInputStream =
                 (TarArchiveInputStream) new ArchiveStreamFactory().createArchiveInputStream("tar", in)) {
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry) debInputStream.getNextEntry()) != null) {
              final File outputFile = new File(outputDir, entry.getName());
              if (!entry.isDirectory()) {
                File parentFolder = outputFile.getParentFile();
                log.debug(String.format("Creating output file %s.%n", outputFile.getAbsolutePath()));
                if (parentFolder.exists() || parentFolder.mkdirs()) {
                  try (OutputStream outputFileStream = new FileOutputStream(outputFile)) {
                    IOUtils.copy(debInputStream, outputFileStream);
                  }
                } else {
                  log.warn(String.format("Skipping file %s because folders could not be created",
                    outputFile.getAbsolutePath()));
                }
              }
            }
          }
        }
      }
      log.debug("Done downloading and decompressing file");
    } catch (MalformedURLException | FileNotFoundException e) {
      throw new FhirPackageNotFoundException(fhirPackage, e);
    } catch (ArchiveException | IOException e) {
      throw new FhirPackageDownloadException(fhirPackage, e);
    }
  }

  private Path getFolderForFhirPackage(VersionedFhirPackage fhirPackage) {
    Path userFolder = new File(System.getProperty("user.home")).toPath();
    Path fhirFolder = userFolder.resolve(".fhir");
    Path packagesFolder = fhirFolder.resolve("packages");
    Path packageFolder = packagesFolder.resolve(fhirPackage.toString());
    return packageFolder.resolve("package");
  }

  public static void main (String[] args) {
    if (args.length != 3) {
      System.out.println("Three arguments are required: FHIR package name, FHIR package version, output file.");
      System.exit(0);
    }

    FhirContext ctx = FhirContext.forR4();
    RedmatchGrammarCodeSystemGenerator generator =
      new RedmatchGrammarCodeSystemGenerator(new Gson(), ctx);
    CodeSystem cs = null;
    try {
      cs = generator.createCodeSystem(new VersionedFhirPackage(args[0], args[1]));
    } catch (IOException e) {
      e.printStackTrace();
    }

    try (FileWriter fw = new FileWriter(args[2])) {
      ctx.newJsonParser().setPrettyPrint(true).encodeResourceToWriter(cs, fw);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
