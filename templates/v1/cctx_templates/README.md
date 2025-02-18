# {{cctx-name}} CCTX

## Overview

This Codebase Change Transaction (CCTX) is designed to {{description}}. It is part of the {{project}} project and is configured for execution {{#container}}within a container environment{{/container}}{{^container}}in a non-container environment{{/^container}}.

## Important Notes

{{#container}}
- **Container Configuration**: This CCTX is specifically configured to run in a container environment. The container project root is set to `{{container-project-root}}`.
- **Execution Constraints**: Due to its container-specific configuration, this CCTX will not run correctly outside of the designated container environment.
- **Rebuilding Requirement**: If you need to run this CCTX in a different environment (e.g., outside the container), you must rebuild it using the CCTX builder with the appropriate settings.
{{/container}}
{{^container}}
- **Non-Container Configuration**: This CCTX is configured to run in a standard, non-containerized environment.
- **Execution Flexibility**: This CCTX can be run directly on the host system without any container-specific requirements.
{{/^container}}

## Configuration Details

- **Project Root**: {{project-root}}
- **CCTX Directory**: {{cctx-dir}}
- **CCTXs Directory**: {{cctxs-dir}}
{{#container}}
- **Container Project Root**: {{container-project-root}}
{{/container}}

## Changes

This CCTX will perform the following changes:

```clojure
{{changes}}
```

## Requirements

{{requires}}

## Execution

To execute this CCTX:

1. Ensure you are in the correct container environment.
2. Navigate to the CCTX directory.
3. Run the CCTX using the appropriate command (e.g., `clojure -M:cctx`).

## Dry Run

Dry run is set to: {{dry-run}}

To perform a dry run, use the appropriate flag or configuration setting when executing the CCTX.

## Rollback

Rollback functionality is set to: {{rollback}}

In case of issues, refer to the rollback instructions provided in the main CCTX documentation.

## Additional Information

For more details on using CCTXs and the overall project structure, please refer to the main project documentation.