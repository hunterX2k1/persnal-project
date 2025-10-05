# How to Build the Face Search Application for Windows

This guide provides step-by-step instructions to turn the provided Python source code into a standalone Windows executable (`.exe`) file.

## Prerequisites

Before you begin, you need to install Python on your Windows computer.

1.  **Download Python:** Go to the official Python website: [https://www.python.org/downloads/](https://www.python.org/downloads/)
2.  **Run the Installer:** Run the downloaded installer.
3.  **IMPORTANT:** On the first screen of the installer, make sure you check the box that says **"Add Python to PATH"**. This is crucial for the following steps to work.
    ![Add Python to PATH](https://docs.python.org/3/_images/win_installer.png)
4.  Continue with the default installation settings.

---

## Build Instructions

### Step 1: Unzip the Project Folder

You will receive a `.zip` file containing the application's source code. Right-click on it and select **"Extract All..."** to unzip it to a location you can easily access, like your Desktop.

You should now have a folder named `content_aggregator`.

### Step 2: Open a Command Prompt in the Project Folder

1.  Open the `content_aggregator` folder that you just unzipped.
2.  In the address bar at the top of the File Explorer window, type `cmd` and press **Enter**.

    ![Open CMD in folder](https://i.imgur.com/O14i48p.png)

This will open a black command prompt window that is already in the correct directory.

### Step 3: Install Required Libraries

In the command prompt window, copy and paste the following command and press **Enter**. This will install all the libraries the application needs to run.

```
pip install -r requirements.txt
```

**Note:** This step may take a considerable amount of time (15-30 minutes or more) as it downloads and installs large files for the face recognition engine. Please be patient and let it complete.

### Step 4: Install the Application Builder (PyInstaller)

Once the previous step is finished, run the following command in the same command prompt window to install `PyInstaller`:

```
pip install pyinstaller
```

### Step 5: Build the `.exe` File

This is the final step. Run the following command in the same command prompt window. This tells `PyInstaller` to build your application.

```
pyinstaller --onefile --windowed --name FaceSearchApp --hidden-import=win32timezone --hidden-import=plyer.platforms.win.filechooser main.py
```

-   `--onefile`: Bundles everything into a single `.exe` file.
-   `--windowed`: Prevents a console window from appearing when you run the app.
-   `--name FaceSearchApp`: Names the final executable file `FaceSearchApp.exe`.

This process may also take a few minutes to complete.

### Step 6: Find and Run Your Application

After the build is complete, you will find a new folder named `dist` inside your `content_aggregator` directory.

1.  Open the `dist` folder.
2.  Inside, you will find **`FaceSearchApp.exe`**. This is your final, standalone application. You can double-click it to run it.

You can copy `FaceSearchApp.exe` to your Desktop or anywhere else on your computer. It no longer needs the other project files to run.