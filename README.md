# HubitatCustom

This set of drivers is being re-written for version 2.2.8 of Hubitat.  It will (eventually) be found as a set of device-independent library modules to be located here: https://github.com/jvmahon/HubitatDriverTools


The 1.1.6 release is an almost complete re-write. In this release, the driver learns about a devices capabilities in one of two ways: 
1. There's an existing database of devices stored in the @Field variable "deviceDatabase" which contains information on devices that I own (Ring G2 Motion sensor, Zooz ZSE18 / Zen25 / Zen26 / Zen30, HomeSeer WD100 / WS100 / WD200 / WS200, Jasco 46201, GE Heavy Duty Switch 14285, Inovelli LZW36). If you have one of those, its in the database.
2. If your device isn't in the deviceDatabase, its information gets pulled from opensmarthouse.or and stored in a state variable. 

I'll document how this all works at some point.

# Advanced Zwave Plus Dimmer driver  and Switch driver- Beta Releases!

The file "Almost Any Z-Wave Plus Dimmer Driver.groovy" is a dimmer driver file, and Almost Any Z-Wave Plus Switch Driver.groovy" a Switch driver that can identify all the parameters for a device and provides input controls allowing the setting of each parameter.

The way this works is that the driver queries the opensmarthouse.com database using the device's manufacturer, device type, and device ID information to retrieve a database record identifying all the parameters for the device. That information is then saved in the device's "state".

## Central Scene - yeah! More than 2 Taps!

The Central Scene handling code now supports up to 5 button taps through the attribute "multiTapButton".

The attribute "multiTapButton" can be used in rule machine to trigger on multiple taps. In Rule Machine, choose the "Custom Attribute" capability, then choose your device, then choose select the "multiTapButton" attribute followed by the equal "=" compariso and the value of the attribute that you want to trigger on.

Whenever a button is tapped, there will be a "multiTapButton" event (in addition to "traditional" pushed and doubleTap events, which you can still use).  The multiTapButton attribute value is specified in decimal notation, with the whole-number part  (i.e., before the decimal) being the button number, and the decimal part indicating the number of taps. Thus, for example, multiTapButton = 4.3  means that button 4 was tapped 3 times.

## Supervision Support
This driver now supports Z-Wave's command supervision class. The Supervision command class allows the Hubitat hub to receive confirmation from a device that a command was received and processed. If the hub doesn't receive that confirmation, it will re-send the command. What this means is improved reliability (for devices that implement the Supervision command class).

## EndPoint Support
The driver now provides better support for endpoints.

On first boot, the driver checks if the device has endpoint support and if any endpoints exist. If so, the driver checks if their deviceNetworkId is in the format expected by the driver. IF it isn't the driver will delete and replace the endpoint child devices (after this is done, you may have to reset rules using those child devices).  Note that the "proper" format is one ending in "-epXXX" where the XXX is replaced by the endpoint number (e.g., "-ep001").

The endpoint code will replace the endpoint driver with either a switch, metering switch, or dimmer component driver.

## Fan Support
###Inovelli LZW36
The driver supports fan control using the Inovelli LZW36 controller.
To support the  Inovelli LZW36, install and initialize the Dimmer driver, then change endpoint #2's driver to "Generic Component Fan Driver". DO NOT use the "Hampton Bay Fan Component" driver.

## Window Shade Support (Maybe)

This is relatively untested, but the driver Should work with window shades. To use with window shades:
* Cut/paste a copy of the driver into Hubitat as a new driver
* Change the name in the metadata to indicate this copy is for Window Shades
* Uncomment the "WindowShade" capability, and comment out the "Switch", "SwitchLevel", and "ChangeLevel" capabilities.



### This is still a work-in-progress. 

Some tips:
* Install the driver on the "Driver Code" page. It will appear with the name "Advanced Zwave Plus Dimmer
* Go to the device that you want to work on and select this driver as the device's driver "Type".
* Click on the "Reset Driver State Data" button to clear the stored "state" inforamtion from the prior driver.
* Click on the "Initialize" control to pull the data from the database and to poll your device for its current parameter settings.
* After this completes, then click on "Save Preferences" and the web page will now refresh. After it refreshes, the "Preferences" area should now show controls for all of the parameters.  If everything worked right, the controls should show the current settings of your device
* You should now be able to change / udpate the parameters.

* Its recommended that you reboot after assigning the driver to your devices.


## Donate
Some have asked if they could donate something to me for this work.  I am not asking for donations for myself. However, if this driver helped you, I ask instead that you take whatever you think you would have donated and find food bank or other charity in your area and donate it to them instead (there are plenty of online foodbank sites). Here's one I can suggest: https://foodbankcenc.org/ . I opened an issue "Donate"- let's hear from those who have given something in response!

# Known Bugs
Version 0.1.0
A Hubitat bug exist in parsing meter reports. You may see occasional parsing errors in the log related to this.

# Version History
0.1.0 - This was a complete rewrite to better handle concurrency and better handle endpoint support.
0.0.7 - Numerous fixes for setting of parameters. Range checking on parameter inputs. Fixed errors in handling meter reports from devices with multiple endpoints. Additional support for multi-endpoint devices.
0.0.6 - Numerous fixes for setting of parameters. Fix to avoid trying to set a "null" parameter. If currently stored paramenter is a "null" then try to re-get parameters on an Update.


