# Dovecot rig for `ImapAclEngineDovecotLiveTest`

Starts Dovecot 2.3.21 with the configuration next to this file, and puts one message in alice's INBOX:

```sh
docker run -d --name dovecot-rig -p 10993:993 \
  -v "$PWD/email-connector-services/src/test/resources/dovecot-rig/dovecot.conf:/etc/dovecot/dovecot.conf:ro" \
  dovecot/dovecot:2.3.21
printf 'Subject: rig\r\n\r\nrig\r\n' | docker exec -i dovecot-rig doveadm save -u alice@dovecot.local
export ALICE_PASSWORD=pass BOB_PASSWORD=pass
mvn -o test -pl email-connector-services -Ddovecot.it=true -Dtest=ImapAclEngineDovecotLiveTest
docker rm -f dovecot-rig
```
