# Codebase Change Transaction (CCTX)

Bringing ACID to the coding experience (if possible). 

## Purpose

The primary purpose of a CCTX is to encourage thoughtful, structured changes to the codebase based on developer sanity, clarity, and quiet judgement. A CCTX is to the intention of the developer what a `git commit` is to a codebase or a transaction is to a database; as ACID as it can get.

And...it's not clear if this project makes any sense. To the extent there are patterns in codebase changes, maybe we can capture them. Maybe macro-esque. Dunno.

## Workflow

1. Create a CCTX:
   ```
   bb cctx_builder.clj <cctx-name> --template <template> --template-version <version> --projects <config-file> --project <project-name>
   ```

2. Activate a CCTX: (not sure what this means yet)
   ```clojure
   (activate-cctx!)
   ```

3. Make your changes and commit them on the CCTX branch.

4. Deactivate a CCTX: (not sure what this means yet)
   ```clojure
   (deactivate-cctx!)
   ```

5. Complete a CCTX:
   Use Git to merge the CCTX branch into your desired target branch.

## Configuration

CCTX uses two main configuration files: `projects.edn` and template configuration files.

### Projects Configuration

The `projects.edn` file defines the projects that CCTX can work with. Example structure:

```edn
{:projects
 {"ProjectName"
  {:name "ProjectName"
   :project-root "/path/to/project"
   :dev-in-container? true
   :container-project-root "/container/path"  ; Optional, required if dev-in-container? is true
   :cctx-dir "dev/cctx"
   :cctxs-dir "dev/cctx/cctxs"
   :project-has-templates false
   :project-templates-dir "path/to/templates"}}}
```

Configuration fields:
- `name`: Project identifier
- `project-root`: Absolute path to project root on host system
- `dev-in-container?`: Whether development happens in a container
- `container-project-root`: Project path inside container (required if dev-in-container? is true)
- `cctx-dir`: Directory for CCTX infrastructure relative to project root
- `cctxs-dir`: Directory for individual CCTXs relative to project root
- `project-has-templates`: Whether project has its own CCTX templates
- `project-templates-dir`: Path to project-specific templates (if project-has-templates is true)

### Templates

CCTX templates are organized by version and define different types of changes. Basic template types include:

```edn
{:basic        ; Simple change with no predefined actions
 {:name "Basic Change"
  :desc "Simple change with no predefined actions"
  :spec {...}}
 
 :transformer  ; Change that transforms data with validation
 {:name "Transformer Change"
  :desc "Change that transforms data with validation"
  :spec {...}}
 
 :script       ; Adds executable script to dev/scripts
 {:name "Add Script"
  :desc "Adds a new executable script"
  :spec {...}}
 
 :bb-script    ; Adds Babashka script to dev/scripts
 {:name "Add Babashka Script"
  :desc "Adds a new Babashka script"
  :spec {...}}}
```

Each template defines:
- Name and description
- Change specification including:
  - Title and description
  - Change operations (add file, transform, etc.)
  - Requirements
  - Rollback and dry-run options

Templates are validated against a schema that ensures required fields and proper structure:

```edn
[:map-of :keyword
 [:map
  [:name string?]
  [:desc string?]
  [:spec [:map
          [:title string?]
          [:description string?]
          [:changes [:sequential ...]]
          [:requires {:optional true} [:sequential keyword?]]
          [:rollback {:optional true} boolean?]
          [:dry-run {:optional true} boolean?]]]]]
```
````
