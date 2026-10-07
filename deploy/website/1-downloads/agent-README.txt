HubidubiTask Agent
==================

Claude Code on a computer in your home, for the HubidubiTask apps' reviews.
Linux or macOS, with Claude Code, Python 3.9 or later, and openssl.

Install, from the folder this file is in:

    sh install.sh

It runs as a service on port 8765 and starts with the computer. At the end
it prints the IP and port to type in the app: Settings, AI, Agent.

If a firewall is on (Omarchy turns ufw on):

    sudo ufw allow from 192.168.0.0/16 to any port 8765 proto tcp

To answer with a model you run, edit ~/.config/hubidubitask-harness/harness.env
and restart it (Linux: systemctl --user restart hubidubitask-harness; macOS:
run install.sh again).

Remove it: sh install.sh --remove

More: the Agent page on the HubidubiTask website.
