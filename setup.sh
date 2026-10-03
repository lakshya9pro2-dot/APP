#!/bin/bash
# setup.sh — Run this ONCE after cloning to bootstrap the project.
# Downloads gradle-wrapper.jar and initializes git if needed.

set -e

echo "=== LiteWeb Extractor Setup ==="

# 1. Generate gradle-wrapper.jar (needs Gradle installed: brew/apt install gradle, or SDKMAN)
WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  if command -v gradle &>/dev/null; then
    gradle wrapper --gradle-version 8.4
  else
    echo "Install Gradle, then run: gradle wrapper --gradle-version 8.4"
    echo "(or just open the project in Android Studio, which does it for you)"
  fi
else
  echo "gradle-wrapper.jar already present."
fi

# 2. Make gradlew executable
chmod +x gradlew
echo "gradlew is executable."

# 3. Initialize git if not already
if [ ! -d ".git" ]; then
  git init
  git add .
  git commit -m "Initial commit: LiteWeb Extractor"
  echo "Git repository initialized."
else
  echo "Git repository already exists."
fi

echo ""
echo "=== Setup complete! ==="
echo ""
echo "Next steps:"
echo "  1. Create a GitHub repo:  https://github.com/new"
echo "  2. Push:  git remote add origin https://github.com/YOUR_USERNAME/LiteWebExtractor.git"
echo "            git push -u origin main"
echo "  3. GitHub Actions will build the APK automatically."
echo "  4. Download APK from: Actions tab → latest run → Artifacts"
echo ""
