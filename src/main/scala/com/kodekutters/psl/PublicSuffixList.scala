package com.kodekutters.psl

import java.net.URL
import com.typesafe.config.{ConfigFactory, Config}
import scala.io.{Codec, Source}


object PublicSuffixList {

  /**
   * URL of the Public Suffix List (PSL). for example:
   *
   * "https://publicsuffix.org/list/public_suffix_list.dat" or
   *
   * "file:///Users/.../src/main/resources/public_suffix_list.dat"
   *
   */
  val PROPERTY_URL = "psl.url"
  /**
   * Character encoding of the list.
   */
  val PROPERTY_CHARSET = "psl.charset"
  /**
   * if true print the basic input checks error messages, else suppress the printing, see BasicChecker
   */
  val PRINT_CHECKS = "psl.printChecks"

  /**
   * loads the default properties from the application.conf file in the resource directory.
   */
  def getDefaults: Config = ConfigFactory.load()

  /**
   * create a PublicSuffixList with custom properties.
   *
   * @param properties the configuration properties for building the link PublicSuffixList.
   * @return a public suffix list created with the given properties
   *
   */
  def apply(properties: Config): PublicSuffixList = {
    try {
      val printFlag = properties.getBoolean(PRINT_CHECKS)
      // default codec is "UTF-8"
      val charset = if (properties.getString(PROPERTY_CHARSET).isEmpty) Codec("UTF-8") else Codec(properties.getString(PROPERTY_CHARSET))
      // the PSL file from the url
      var sourceBuffer = Source.fromURL(new URL(properties.getString(PROPERTY_URL)))
      // parse the rules file into a list of rules and add the default rule to it
      val rules = Parser().parse(sourceBuffer, charset) :+ Rule.DEFAULT_RULE
      val ruleFinder = new RuleFinder(rules)
      new PublicSuffixList(ruleFinder, charset, printFlag)
    } catch {
      case e: Exception => println("exception caught: " + e); null
    }
  }

  /**
   * create a PublicSuffixList with the default properties.
   */
  def apply(): PublicSuffixList = apply(getDefaults)

}

/**
 * The Public Suffix List API.
 *
 * Use the methods with UTF-8 domain names or Punycode encoded ASCII domain names.
 * The methods will return the results in the same type as the input.
 *
 * references:
 *
 * https://publicsuffix.org/
 *
 * https://github.com/whois-server-list/public-suffix-list <-- the main java code source
 *
 * https://github.com/wrangr/psl
 *
 * @param ruleFinder    the rule finder
 * @param charset  the character encoding of the list
 *
 */
final class PublicSuffixList(val ruleFinder: RuleFinder, val charset: Codec, val printFlag: Boolean) {

  /**
   * gets the registrable domain.
   *
   * E.g. "www.example.net" and "example.net" will return "example.net".
   * Null, and empty string or domains with a leading dot will return None.
   *
   * @param domain the domain name
   * @return the registrable domain, None if the domain is not registrable
   */
  def getRegistrableDomain(domain: String): Option[String] = {
    if (isValidInput(domain)) {
      val punycode = new PunycodeAutoDecoder()
      val decodedDomain = punycode.decode(domain)
      doGetPublicSuffix(decodedDomain) match {
        case None => None
        case Some(suffix) =>
          if (decodedDomain == suffix) None
          else {
            val suffixLabels = Util.splitLabels(suffix)
            val labels = Util.splitLabels(decodedDomain)
            val offset = labels.length - suffixLabels.length - 1
            val registrableDomain = Util.joinLabels(labels.slice(offset, labels.length).toList)
            Option(punycode.recode(registrableDomain))
          }
      }
    }
    else
      None
  }

  /**
   * determines if a domain is registrable.
   *
   * E.g. "example.net" is registrable, "www.example.net" and "net" are not.
   *
   * @param domain the domain name
   * @return { @code true} if the domain is registrable
   */
  def isRegistrable(domain: String): Boolean = getRegistrableDomain(domain).contains(domain.toLowerCase)

  /**
   * returns the public suffix from a domain.
   *
   * If the domain is already a public suffix, it will be returned unchanged as an option.
   * E.g. "www.example.net" will return "net".
   *
   * @param domain the domain name
   * @return the public suffix, None if none matched
   */
  def getPublicSuffix(domain: String): Option[String] = if (isValidInput(domain)) doGetPublicSuffix(domain) else None

  private def doGetPublicSuffix(domain: String): Option[String] = {
    val punycode = new PunycodeAutoDecoder()
    val decodedDomain = punycode.recode(domain)
    ruleFinder.findRule(decodedDomain).flatMap(rule => rule.doMatch(decodedDomain).map(dmain => punycode.decode(dmain)))
  }

  /**
   * determines if a domain is a public suffix or not.
   *
   * Example: "com" is a public suffix, "example.com" is not.
   *
   * @param domain the domain name
   * @return { @code true} if the domain is a public suffix
   */
  def isPublicSuffix(domain: String): Boolean = if (isValidInput(domain)) doGetPublicSuffix(domain).contains(domain.toLowerCase) else false

  private def isValidInput(domain: String): Boolean = !(domain == null || domain.isEmpty || domain.charAt(0) == '.' || !BasicChecker.isValid(domain, printFlag))

  /**
   * for testing, see TestApp
   */
  def checkPublicSuffix(domain: String, expected: String): Unit = {
    getRegistrableDomain(domain) match {
      case None => println(Option(expected).isEmpty + "  domain: " + domain + " expected: " + expected)
      case Some(mtch) => println((mtch.toLowerCase == expected) + "  domain: " + domain + " expected: " + expected)
    }
  }

}