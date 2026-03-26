#!/bin/bash
TICKETS=$(klist -s)
STATUS=$?
if [[ $STATUS -eq 1 ]]; then
    kinit
fi
export MYPORT=`id -u`
export PS1='pgtunnel> '
ssh -K -o StrictHostKeyChecking=accept-new -N -L 127.0.0.1:$MYPORT:/var/run/postgresql/.s.PGSQL.5432 aziak &
MYPID=$!
bash
kill -9 $MYPID
