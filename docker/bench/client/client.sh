echo "Sleeping"
sleep 20
echo "Warm up ..."
wrk -R 1000 -t6 -c200 -d40s -H "Host: test.foo.bar" --latency http://heimdallr:8091/
# wrk2 -R 1000 -t6 -c200 -d40s -H "Host: test.foo.bar" --latency http://heimdallr:8091/
echo "Warm up done !"
sleep 10
echo "Bench ..."
wrk -R 8000 -t80 -c800 -d60s -H "Host: test.foo.bar" --latency http://heimdallr:8091/
# wrk2 -R 8000 -t80 -c800 -d60s -H "Host: test.foo.bar" --latency http://heimdallr:8091/

echo "Testing on graal VM"

echo "Warm up ..."
wrk -R 1000 -t6 -c200 -d40s -H "Host: test.foo.bar" --latency http://heimdallrgraalvm:8092/
echo "Warm up done !"
sleep 10
echo "Bench ..."
wrk -R 8000 -t80 -c800 -d60s -H "Host: test.foo.bar" --latency http://heimdallrgraalvm:8092/
