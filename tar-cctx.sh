#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -e

# Variables
PROJECT_ROOT="/home/chip/Documents/cctx"
PARENT_DIR=$(dirname "$PROJECT_ROOT")
TIMESTAMP=$(date +"%Y-%m-%dT%H-%M")
TAR_NAME="cctx${TIMESTAMP}.tar.gz"
EXCLUDE_FILE="$PROJECT_ROOT/.exclude-from-tar"
DEST_DIR="/srv/raid/cctx-project-backup"
TREE_FILE="$PROJECT_ROOT/project_tree.txt"

# Check if the exclusion file exists
if [ ! -f "$EXCLUDE_FILE" ]; then
  echo "Error: Exclusion file $EXCLUDE_FILE does not exist."
  exit 1
fi

# Check if the destination directory exists
if [ ! -d "$DEST_DIR" ]; then
  echo "Error: Destination directory $DEST_DIR does not exist."
  exit 1
fi

# Generate the tree structure of the project directory
cd "$PROJECT_ROOT"
if command -v tree &>/dev/null; then
  tree > "$TREE_FILE"
else
  echo "Error: 'tree' command is not available. Please install it to continue."
  exit 1
fi

# Change to the parent directory of $PROJECT_ROOT
cd "$PARENT_DIR"

# Create the tar archive, including the tree file
tar --exclude-from="$EXCLUDE_FILE" -cvzf "$TAR_NAME" "cctx"

# Move the tar archive to the destination directory
sudo mv "$TAR_NAME" "$DEST_DIR"

# Delete the tree file after the tarball is moved
rm -f "$TREE_FILE"

# Output the result
echo "Tar archive created and moved to: $DEST_DIR/$TAR_NAME"
