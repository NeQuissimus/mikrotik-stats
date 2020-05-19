# mikrotik-stats
Export wireless clients from RouterOS device

Data will be output in Prometheus format

## Usage

- Enable the API service on the device
- Add an API user
```
/user group add name=prometheus policy=api,read,winbox
/user add name=prometheus group=prometheus password=changeme
```
- Run script in an HTTP "server" :D
```
while true; do { echo -ne "HTTP/1.0 200 OK\r\n\r\n"; nix-shell -p ammonite --command "export MIKROTIK_USER=prometheus; export MIKROTIK_PASSWORD=changeme; export MIKROTIK_IPS='10.0.0.2,10.0.0.3'; amm -s ./mikrotik.sc"; } | nc -N -l 8080; done
```
