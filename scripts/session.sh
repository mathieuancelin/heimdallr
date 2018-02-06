#!/bin/bash

LOCATION=`pwd`
SESSION_NAME="heimdallr-dev"

tmux start-server;
cd $LOCATION

tmux new-session -d -s $SESSION_NAME

# Create other windows.
tmux new-window -c $LOCATION -t $SESSION_NAME:1 -n heimdallr-build
tmux new-window -c $LOCATION -t $SESSION_NAME:2 -n heimdallr-editor

# Window "heimdallr-editor"
tmux send-keys -t $SESSION_NAME:2 vim C-m

# Window "heimdallr-build"
tmux split-window -h -c $LOCATION -t $SESSION_NAME:1
tmux split-window -v -l 2 -c $LOCATION -t $SESSION_NAME:1.1 
tmux split-window -v -p 50 -c $LOCATION -t $SESSION_NAME:1.3 

tmux send-keys -t $SESSION_NAME:1.1 clear C-m
tmux send-keys -t $SESSION_NAME:1.2 clear C-m
tmux send-keys -t $SESSION_NAME:1.3 clear C-m
tmux send-keys -t $SESSION_NAME:1.4 clear C-m

tmux send-keys -t $SESSION_NAME:1.1 "sbt '~reStart'"  C-m
tmux send-keys -t $SESSION_NAME:1.2 "sh ./scripts/fmt.sh"
tmux send-keys -t $SESSION_NAME:1.3 'wrk -t2 -c200 -d60s -H "Host: test.foo.bar" --latency http://127.0.0.1:8081/'
tmux send-keys -t $SESSION_NAME:1.4 "git status" C-m

tmux select-window -t $SESSION_NAME:1
tmux select-pane -t $SESSION_NAME:1.1

tmux -u attach-session -t $SESSION_NAME
