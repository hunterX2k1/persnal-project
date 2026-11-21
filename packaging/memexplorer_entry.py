"""PyInstaller entry point -> produces the memexplorer executable."""
import sys

from memexplorer.cli import main

if __name__ == "__main__":
    sys.exit(main())
