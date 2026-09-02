// prodos status bar widget for Quickshell (Omarchy Quattro's shell) — Phase 8.
//
// Shows the native inference-connection dot + the inbox count, polling the
// prodos CLIs. No browser, no WebView. Add to the Omarchy Quickshell bar with:
//   import "/etc/prodos/quickshell"
//   ProdosStatus {}
//
// Targets Quickshell's Io.Process API; exact import paths may need adjusting to
// the installed Quickshell version on the image.
import QtQuick
import Quickshell
import Quickshell.Io

Row {
    id: root
    spacing: 8

    property bool inferenceUp: false
    property string model: ""
    property int inboxCount: 0

    // Poll inference status via `prodos-agent status --json`.
    Process {
        id: statusProc
        command: ["prodos-agent", "status", "--json"]
        stdout: StdioCollector {
            onStreamFinished: {
                try {
                    const d = JSON.parse(this.text)
                    root.inferenceUp = d.connected === true
                    root.model = (d.models && d.models.length) ? d.models[0] : ""
                } catch (e) { root.inferenceUp = false }
            }
        }
    }

    // Poll inbox count via `prodos inbox --json`.
    Process {
        id: inboxProc
        command: ["prodos", "inbox", "--json"]
        stdout: StdioCollector {
            onStreamFinished: {
                try { root.inboxCount = JSON.parse(this.text).length }
                catch (e) { root.inboxCount = 0 }
            }
        }
    }

    Timer {
        interval: 15000; running: true; repeat: true; triggeredOnStart: true
        onTriggered: { statusProc.running = true; inboxProc.running = true }
    }

    // Inference dot: green when connected, grey when offline.
    Text {
        text: root.inferenceUp ? "●" : "○"
        color: root.inferenceUp ? "#22aa66" : "#999999"
    }
    Text {
        text: root.inferenceUp ? (root.model || "inference") : "offline"
        color: "#dddddd"
    }
    Text { text: "· 󰚄 " + root.inboxCount; color: "#dddddd" }

    // Click the widget to open the capture prompt.
    MouseArea {
        anchors.fill: parent
        onClicked: captureProc.running = true
        Process { id: captureProc; command: ["/usr/local/bin/prodos-capture-gui"] }
    }
}
