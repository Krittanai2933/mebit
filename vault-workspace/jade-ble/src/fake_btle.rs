use std::collections::BTreeSet;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

use btleplug::Error;
use btleplug::api::{
    BDAddr, Central, CentralEvent, CentralState, Characteristic, Descriptor, PeripheralProperties,
    ScanFilter, Service, ValueNotification, WriteType,
};
use btleplug::platform::PeripheralId;
use futures_util::Stream;

type Result<T> = std::result::Result<T, Error>;
type Events = Pin<Box<dyn Stream<Item = CentralEvent> + Send>>;
type Notifications = Pin<Box<dyn Stream<Item = ValueNotification> + Send>>;

#[derive(Clone, Default)]
pub struct FakeCentral {
    pub calls: Arc<Mutex<Vec<&'static str>>>,
    pub devices: Vec<FakePeripheral>,
}

#[derive(Clone, Debug)]
pub struct FakePeripheral(pub std::result::Result<Option<PeripheralProperties>, String>);

impl FakeCentral {
    pub fn calls(&self) -> Vec<&'static str> {
        self.calls.lock().unwrap().clone()
    }

    fn record(&self, call: &'static str) {
        self.calls.lock().unwrap().push(call);
    }
}

#[async_trait::async_trait]
impl Central for FakeCentral {
    type Peripheral = FakePeripheral;
    async fn events(&self) -> Result<Events> {
        unimplemented!()
    }
    async fn start_scan(&self, _: ScanFilter) -> Result<()> {
        self.record("start_scan");
        Ok(())
    }
    async fn stop_scan(&self) -> Result<()> {
        self.record("stop_scan");
        Ok(())
    }
    async fn peripherals(&self) -> Result<Vec<FakePeripheral>> {
        Ok(self.devices.clone())
    }
    async fn peripheral(&self, _: &PeripheralId) -> Result<FakePeripheral> {
        unimplemented!()
    }
    async fn add_peripheral(&self, _: &PeripheralId) -> Result<FakePeripheral> {
        unimplemented!()
    }
    async fn clear_peripherals(&self) -> Result<()> {
        unimplemented!()
    }
    async fn adapter_info(&self) -> Result<String> {
        unimplemented!()
    }
    async fn adapter_state(&self) -> Result<CentralState> {
        unimplemented!()
    }
}

#[async_trait::async_trait]
impl btleplug::api::Peripheral for FakePeripheral {
    async fn properties(&self) -> Result<Option<PeripheralProperties>> {
        self.0.clone().map_err(Error::RuntimeError)
    }
    fn id(&self) -> PeripheralId {
        unimplemented!()
    }
    fn address(&self) -> BDAddr {
        unimplemented!()
    }
    fn mtu(&self) -> u16 {
        unimplemented!()
    }
    fn services(&self) -> BTreeSet<Service> {
        unimplemented!()
    }
    async fn is_connected(&self) -> Result<bool> {
        unimplemented!()
    }
    async fn connect(&self) -> Result<()> {
        unimplemented!()
    }
    async fn disconnect(&self) -> Result<()> {
        unimplemented!()
    }
    async fn discover_services(&self) -> Result<()> {
        unimplemented!()
    }
    async fn write(&self, _: &Characteristic, _: &[u8], _: WriteType) -> Result<()> {
        unimplemented!()
    }
    async fn read(&self, _: &Characteristic) -> Result<Vec<u8>> {
        unimplemented!()
    }
    async fn subscribe(&self, _: &Characteristic) -> Result<()> {
        unimplemented!()
    }
    async fn unsubscribe(&self, _: &Characteristic) -> Result<()> {
        unimplemented!()
    }
    async fn notifications(&self) -> Result<Notifications> {
        unimplemented!()
    }
    async fn write_descriptor(&self, _: &Descriptor, _: &[u8]) -> Result<()> {
        unimplemented!()
    }
    async fn read_descriptor(&self, _: &Descriptor) -> Result<Vec<u8>> {
        unimplemented!()
    }
}
